package gitgalaxy.backend.service;

import gitgalaxy.backend.model.RepoResponse;
import gitgalaxy.backend.repository.RepoRepository;
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
    public RepoResponse getRepo(String owner, String repo) {
        return repoRepository.findByOwnerAndName(owner, repo)
                .map(RepoResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found: " + owner + "/" + repo));
    }
}
