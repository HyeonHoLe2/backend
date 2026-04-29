package gitgalaxy.backend.controller;

import gitgalaxy.backend.entity.RepoAiSummary;
import gitgalaxy.backend.repository.RepoAiSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RepoAiSummaryController {

    private final RepoAiSummaryRepository repoAiSummaryRepository;

    @GetMapping("/repos/{owner}/{repo}/summary")
    public ResponseEntity<String> getSummary(
            @PathVariable String owner,
            @PathVariable String repo
    ) {
        return repoAiSummaryRepository
                .findByOwnerAndRepo(owner, repo)
                .map(RepoAiSummary::getSummary)
                .map(summary -> summary != null && !summary.isBlank()
                        ? ResponseEntity.ok(summary)
                        : ResponseEntity.status(HttpStatus.NO_CONTENT).body("")
                )
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("summary not found"));
    }
}

