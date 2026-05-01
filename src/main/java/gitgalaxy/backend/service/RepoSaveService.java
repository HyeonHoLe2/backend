package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.RepoMeta;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RepoSaveService {

    private final RepoRepository repoRepository;

    @Transactional
    public Repo saveOrUpdate(RepoMeta meta) {
        Repo repo = repoRepository.findByFullName(meta.fullName()).orElseGet(Repo::new);
        repo.setFullName(meta.fullName());
        repo.setOwner(meta.ownerName());
        repo.setName(meta.repoName());
        repo.setLanguage(meta.primaryLanguage());
        repo.setStarCount((int) meta.starsTotal().longValue());
        repo.setDefaultBranch(meta.defaultBranch());
        repo.setTracked(true);
        return repoRepository.save(repo);
    }
}
