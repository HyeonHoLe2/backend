package gitgalaxy.backend.service;

import gitgalaxy.backend.model.RepoResponse;
import gitgalaxy.backend.repository.RepoRepository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RepoService {

    private final RepoRepository repoRepository;

    @Transactional(readOnly = true)
    public List<RepoResponse> search(String keyword) {
        return repoRepository.searchByKeyword(keyword)
                .stream()
                .map(RepoResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RepoResponse getRepoById(Long repoId) {
        return repoRepository.findById(repoId)
                .map(RepoResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found: " + repoId));
    }

    @Transactional(readOnly = true)
    public RepoResponse getRepo(String owner, String repo) {
        return repoRepository.findByOwnerAndName(owner, repo)
                .map(RepoResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found: " + owner + "/" + repo));
    }
}
