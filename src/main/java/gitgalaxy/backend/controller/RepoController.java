package gitgalaxy.backend.controller;

<<<<<<< feat/2-scheduler
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.entity.RepoHourlyMetrics;
import gitgalaxy.backend.model.RepoListItemDto;
import gitgalaxy.backend.model.TimelinePointDto;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /repos                              → tracked repo 전체 목록 (스코어 포함)
 * GET /repos/trending                     → brightnessScore 상위 목록 (?limit=50)
 * GET /repos/{owner}/{repo}               → repo 상세 + 최신 스코어
 * GET /repos/{owner}/{repo}/timeline      → 시간별 메트릭/스코어 (?hours=24)
 * GET /repos/{owner}/{repo}/explain       → RAG 설명 (?q=질문)
 * GET /repos/search                       → 글로벌 RAG 검색 (?q=질문)
 */
=======
import gitgalaxy.backend.model.RepoResponse;
import gitgalaxy.backend.service.RepoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

>>>>>>> main
@RestController
@RequestMapping("/repos")
@RequiredArgsConstructor
public class RepoController {

<<<<<<< feat/2-scheduler
    private final RepoRepository repoRepository;
    private final RepoHourlyMetricsRepository metricsRepository;
    private final RagService ragService;

    @GetMapping
    public List<RepoListItemDto> list() {
        Map<String, RepoHourlyMetrics> latestMap = metricsRepository.findLatestPerRepo()
                .stream()
                .collect(Collectors.toMap(
                        m -> m.getRepoOwner() + "/" + m.getRepoName(),
                        m -> m
                ));
        return repoRepository.findByTrackedTrue()
                .stream()
                .map(repo -> RepoListItemDto.of(repo, latestMap.get(repo.getFullName())))
                .sorted(Comparator.comparingDouble(RepoListItemDto::brightnessScore).reversed())
                .toList();
    }

    @GetMapping("/trending")
    public List<RepoListItemDto> trending(@RequestParam(defaultValue = "50") int limit) {
        List<RepoHourlyMetrics> topMetrics = metricsRepository.findTrending(limit);

        Map<String, Repo> repoMap = repoRepository.findByTrackedTrue()
                .stream()
                .collect(Collectors.toMap(Repo::getFullName, r -> r));

        return topMetrics.stream()
                .map(m -> {
                    Repo repo = repoMap.get(m.getRepoOwner() + "/" + m.getRepoName());
                    if (repo == null) {
                        repo = Repo.builder()
                                .fullName(m.getRepoOwner() + "/" + m.getRepoName())
                                .owner(m.getRepoOwner())
                                .name(m.getRepoName())
                                .build();
                    }
                    return RepoListItemDto.of(repo, m);
                })
                .toList();
    }

    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<RepoListItemDto> getRepo(
            @PathVariable String owner,
            @PathVariable String repo) {
        return repoRepository.findByFullName(owner + "/" + repo)
                .map(r -> {
                    RepoHourlyMetrics latest = metricsRepository
                            .findTopByRepoOwnerAndRepoNameOrderByBucketDesc(owner, repo)
                            .orElse(null);
                    return ResponseEntity.ok(RepoListItemDto.of(r, latest));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{owner}/{repo}/timeline")
    public List<TimelinePointDto> timeline(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(defaultValue = "24") int hours) {
        LocalDateTime to   = LocalDateTime.now();
        LocalDateTime from = to.minusHours(hours);
        return metricsRepository
                .findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(owner, repo, from, to)
                .stream()
                .map(TimelinePointDto::from)
                .toList();
    }

    @GetMapping("/{owner}/{repo}/explain")
    public Map<String, String> explain(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam String q) {
        String answer = ragService.explain(owner, repo, q);
        return Map.of("repo", owner + "/" + repo, "question", q, "answer", answer);
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
=======
    private final RepoService repoService;

    @GetMapping("/search")
    public ResponseEntity<List<RepoResponse>> search(@RequestParam String q) {
        return ResponseEntity.ok(repoService.search(q));
    }

    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<RepoResponse> getRepo(
            @PathVariable String owner,
            @PathVariable String repo) {
        return ResponseEntity.ok(repoService.getRepo(owner, repo));
>>>>>>> main
    }
}
