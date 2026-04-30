package gitgalaxy.backend.controller;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.model.RepoInput;
import gitgalaxy.backend.model.RepoResult;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.service.EmbeddingPipelineService;
import gitgalaxy.backend.service.GhArchiveService;
import gitgalaxy.backend.service.RepoCollectorService;
import gitgalaxy.backend.service.ScoreService;
import gitgalaxy.backend.service.TrendingRssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 운영/개발용 관리 API.
 *
 * POST /admin/collect                              → DB tracked repo 기준 전체 수집 (GitHub API, 주1회 배치와 동일)
 * POST /admin/collect/{owner}/{repo}               → 단일 repo 즉시 수집
 * POST /admin/gharchive/{year}/{month}/{day}/{hour} → 특정 시간 GH Archive 수동 처리
 * POST /admin/trending                             → Trending RSS 즉시 실행
 * POST /admin/score                                → 전체 tracked repo 스코어 재계산
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final RepoCollectorService repoCollectorService;
    private final GhArchiveService ghArchiveService;
    private final TrendingRssService trendingRssService;
    private final ScoreService scoreService;
    private final RepoRepository repoRepository;
    private final EmbeddingPipelineService embeddingPipelineService;

    /** GitHub API 기반 doc 수집 (tracked repo 전체) */
    @PostMapping("/collect")
    public Map<String, Object> collectAll() {
        log.info("수동 전체 수집 트리거");
        List<RepoResult> results = repoCollectorService.runBatch();

        long success = results.stream().filter(r -> "success".equals(r.getStatus())).count();
        long failed  = results.stream().filter(r -> "failed".equals(r.getStatus())).count();
        long skipped = results.stream().filter(r -> "skipped".equals(r.getStatus())).count();

        return Map.of("total", results.size(), "success", success, "failed", failed, "skipped", skipped);
    }

    /** GitHub API 기반 doc 수집 (단일 repo) */
    @PostMapping("/collect/{owner}/{repo}")
    public RepoResult collectOne(@PathVariable String owner, @PathVariable String repo) {
        log.info("수동 단일 수집 트리거: {}/{}", owner, repo);
        return repoCollectorService.collectRepo(new RepoInput(owner, repo));
    }

    /**
     * GH Archive 특정 시간 수동 처리 + 임베딩.
     * 예: POST /admin/gharchive/2025/4/29/14 → 2025-04-29 14:00 처리
     */
    @PostMapping("/gharchive/{year}/{month}/{day}/{hour}")
    public Map<String, Object> processGhArchive(
            @PathVariable int year, @PathVariable int month,
            @PathVariable int day,  @PathVariable int hour) {

        LocalDateTime hourBucket = LocalDateTime.of(year, month, day, hour, 0);
        log.info("GH Archive 수동 처리: {}", hourBucket);

        List<ChunkDocument> chunks = ghArchiveService.processHour(hourBucket);
        embeddingPipelineService.embedAndStore(chunks);

        return Map.of("hourBucket", hourBucket.toString(), "chunksExtracted", chunks.size());
    }

    /** Trending RSS 즉시 실행 */
    @PostMapping("/trending")
    public Map<String, Object> runTrending() {
        log.info("Trending RSS 수동 트리거");
        int count = trendingRssService.discoverAndUpsert();
        return Map.of("upserted", count);
    }

    /** 전체 tracked repo 스코어 재계산 */
    @PostMapping("/score")
    public Map<String, Object> recalculateScores() {
        log.info("스코어 재계산 수동 트리거");
        long[] counts = {0};
        repoRepository.findByTrackedTrue().forEach(repo -> {
            scoreService.calculateAndSave(repo.getOwner(), repo.getName());
            counts[0]++;
        });
        return Map.of("scored", counts[0]);
    }
}
