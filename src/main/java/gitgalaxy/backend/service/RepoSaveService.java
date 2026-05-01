package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.RepoMeta;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RepoSaveService {

    private final RepoRepository repoRepository;

    @Transactional
    public Repo saveOrUpdate(String owner, String repoName, RepoMeta meta) {
        String fullName = owner + "/" + repoName;
        Repo repo = repoRepository.findByFullName(fullName).orElseGet(Repo::new);
        repo.setFullName(fullName);
        repo.setOwner(owner);
        repo.setName(repoName);
        repo.setDescription(meta.description());
        repo.setStarCount(meta.stargazersCount());
        repo.setDefaultBranch(meta.defaultBranch());
        repo.setTracked(true);
        repo.setLastCollectedAt(LocalDateTime.now());
        return repoRepository.save(repo);
    }
}
