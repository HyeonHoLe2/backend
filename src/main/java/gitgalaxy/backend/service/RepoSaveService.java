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
        repo.setOwnerName(meta.ownerName());
        repo.setOwnerId(meta.ownerId());
        repo.setRepoName(meta.repoName());
        repo.setRepoUrl(meta.repoUrl());
        repo.setPrimaryLanguage(meta.primaryLanguage());
        repo.setTopics(meta.topics());
        repo.setStarsTotal(meta.starsTotal());
        repo.setForksTotal(meta.forksTotal());
        return repoRepository.save(repo);
    }
}
