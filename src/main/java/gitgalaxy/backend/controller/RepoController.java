package gitgalaxy.backend.controller;

import gitgalaxy.backend.model.RepoResponse;
import gitgalaxy.backend.service.RepoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;

    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<RepoResponse> getRepo(
            @PathVariable String owner,
            @PathVariable String repo) {
        return ResponseEntity.ok(repoService.getRepo(owner, repo));
    }
}
