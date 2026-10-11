package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.RepoMeta;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RepoSaveService {

    private final RepoRepository repoRepository;

    /**
     * DB에만 저장하고, 검색에 쓰이는 필드가 바뀐 경우에만 ES 반영 대기로 표시(es_synced_at = NULL).
     * ES 색인은 RepoEsSyncService가 따로 처리 → ES 장애·지연이 저장을 막거나 DB 커넥션을 붙잡지 않고,
     * 색인 실패분도 다음 주기에 다시 반영됨. 스타 수 등은 검색 결과를 DB에서 읽으므로 재색인 불필요.
     */
    @Transactional
    public Repo saveOrUpdate(String owner, String repoName, RepoMeta meta) {
        String fullName = owner + "/" + repoName;
        Repo repo = repoRepository.findByFullName(fullName).orElseGet(Repo::new);
        boolean searchChanged = repo.getId() == null
                || !repo.isTracked()
                || !Objects.equals(repo.getOwner(), owner)
                || !Objects.equals(repo.getName(), repoName)
                || !Objects.equals(repo.getDescription(), meta.description());
        repo.setFullName(fullName);
        repo.setOwner(owner);
        repo.setName(repoName);
        repo.setDescription(meta.description());
        repo.setStarCount(meta.stargazersCount());
        repo.setForkCount(meta.forksCount());
        repo.setOpenIssuesCount(meta.openIssuesCount());
        repo.setDefaultBranch(meta.defaultBranch());
        repo.setLanguage(meta.language());
        repo.setTracked(true);
        repo.setLastCollectedAt(LocalDateTime.now());
        if (searchChanged) repo.setEsSyncedAt(null);
        return repoRepository.save(repo);
    }

    /**
     * GitHub에서 이름 변경·이전된 repo 반영. 새 이름이 이미 등록돼 있으면(따로 발견된 경우) 옛 행은 추적 해제,
     * 아니면 같은 행의 이름을 바꿔 id·ES 문서를 유지. 반환: renamed | merged
     */
    @Transactional
    public String applyMove(Repo repo, String newFullName, RepoMeta meta) {
        String[] p = newFullName.split("/", 2);
        if (repoRepository.findByFullName(newFullName).isPresent()) {
            untrack(repo);
            return "merged";
        }
        repo.setFullName(newFullName);
        repo.setOwner(p[0]);
        repo.setName(p[1]);
        repo.setEsSyncedAt(null); // 이름이 바뀌었으므로 재색인 (아래 saveOrUpdate는 바뀐 이름으로 찾아 변경 없음으로 봄)
        repoRepository.saveAndFlush(repo);
        saveOrUpdate(p[0], p[1], meta);
        return "renamed";
    }

    /** 삭제·비공개 전환된 repo → 추적 해제 */
    @Transactional
    public void untrack(Repo repo) {
        repo.setTracked(false);
        repo.setLastCollectedAt(LocalDateTime.now());
        repo.setEsSyncedAt(null);
        repoRepository.save(repo);
    }
}
