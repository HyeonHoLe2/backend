package gitgalaxy.backend.controller;

import gitgalaxy.backend.entity.RepoAiSummary;
import gitgalaxy.backend.repository.RepoAiSummaryRepository;
import gitgalaxy.backend.service.AiSummerize;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RepoAiSummaryController {

    private final RepoAiSummaryRepository repoAiSummaryRepository;
    private final AiSummerize aiSummerize;

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

    @PostMapping("/repos/{owner}/{repo}/summary")
    public ResponseEntity<Map<String, String>> generateSummary(
            @PathVariable String owner,
            @PathVariable String repo
    ) {
        String summary = aiSummerize.summarize(owner, repo);
        if (summary == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "README를 찾을 수 없거나 요약에 실패했습니다."));
        }
        return ResponseEntity.ok(Map.of("owner", owner, "repo", repo, "summary", summary));
    }
}
