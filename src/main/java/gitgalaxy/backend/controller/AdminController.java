package gitgalaxy.backend.controller;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.service.EmbeddingPipelineService;
import gitgalaxy.backend.service.GhArchiveService;
import gitgalaxy.backend.service.ScoreService;
import gitgalaxy.backend.service.TrendingRssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 운영/개발용 관리 API (프론트 미사용).
 *
 * POST /admin/gharchive/{year}/{month}/{day}/{hour} → GH Archive 수동 처리
 * POST /admin/trending                             → RSS 즉시 실행 + README 임베딩
 * POST /admin/score                                → 전체 repo 스코어 재계산
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final GhArchiveService ghArchiveService;
    private final TrendingRssService trendingRssService;
    private final ScoreService scoreService;
    private final RepoRepository repoRepository;
    private final EmbeddingPipelineService embeddingPipelineService;

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

    /** Trending RSS 즉시 실행 + README 임베딩 */
    @PostMapping("/trending")
    public Map<String, Object> runTrending() {
        log.info("Trending RSS 수동 트리거");
        List<ChunkDocument> chunks = trendingRssService.discoverAndUpsert();
        embeddingPipelineService.embedAndStore(chunks);
        return Map.of("reposDiscovered", "완료", "chunksEmbedded", chunks.size());
    }

    /** 전체 tracked repo 스코어 재계산 */
    @PostMapping("/score")
    public Map<String, Object> recalculateScores() {
        log.info("스코어 재계산 수동 트리거");
        long[] counts = {0};
        LocalDateTime bucket = LocalDateTime.now().minusHours(1).withMinute(0).withSecond(0).withNano(0);
        repoRepository.findByTrackedTrue().forEach(repo -> {
            scoreService.calculateAndSave(repo.getOwner(), repo.getName(), bucket);
            counts[0]++;
        });
        return Map.of("scored", counts[0]);
    }
}
