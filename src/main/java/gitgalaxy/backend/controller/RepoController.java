package gitgalaxy.backend.controller;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 프론트엔드 대상 Repo 조회 + RAG 설명 API.
 *
 * GET  /repos                          → 수집된 repo 전체 목록
 * GET  /repos/{owner}/{repo}           → repo 상세 (star, branch 등)
 * GET  /repos/{owner}/{repo}/explain   → RAG 기반 설명 생성 (?q=질문)
 * GET  /repos/search                   → 전체 repo 대상 글로벌 검색 (?q=질문)
 */
@RestController
@RequestMapping("/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoRepository repoRepository;
    private final RagService ragService;

    @GetMapping
    public List<Repo> list() {
        return repoRepository.findAll();
    }

    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<Repo> getRepo(
            @PathVariable String owner,
            @PathVariable String repo) {
        return repoRepository.findByFullName(owner + "/" + repo)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{owner}/{repo}/explain")
    public Map<String, String> explain(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam String q) {
        String answer = ragService.explain(owner, repo, q);
        return Map.of(
                "repo", owner + "/" + repo,
                "question", q,
                "answer", answer
        );
    }

    @GetMapping("/search")
    public Map<String, String> search(@RequestParam String q) {
        String answer = ragService.explainGlobal(q);
        return Map.of("question", q, "answer", answer);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception e) {
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
    }
}
