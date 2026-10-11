package gitgalaxy.backend.controller;

import gitgalaxy.backend.service.AdminAsyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 운영/개발용 관리 API (프론트 미사용).
 *
 * POST /admin/gharchive/{year}/{month}/{day}/{hour} → GH Archive 수동 처리 (비동기)
 * GET  /admin/gharchive/status                       → 실패·재시도 대기·포기(dead) 시간대 목록
 * POST /admin/score                                → 전체 repo 스코어 재계산 (비동기)
 * POST /admin/trending/snapshot                     → 지금 스타 스냅샷 + 오늘(UTC) 순위 갱신 (비동기)
 * POST /admin/trending/daily/{date}                 → 그날 순위 재계산 + 같은 날 활동량(별 밝기) 집계 (비동기)
 * POST /admin/retention?dryRun=true                 → repo 보관 정책 (dryRun이면 삭제 대상 개수만)
 * POST /admin/repo-embeddings/backfill              → 레포 임베딩 없는 repo 일괄 생성 + 유사 레포 전체 계산 (비동기)
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AdminAsyncService adminAsyncService;
    private final gitgalaxy.backend.service.GhArchiveHourService ghArchiveHourService;
    private final gitgalaxy.backend.service.RepoRetentionService repoRetentionService;

    @PostMapping("/gharchive/{year}/{month}/{day}/{hour}")
    public ResponseEntity<Map<String, Object>> processGhArchive(
            @PathVariable int year, @PathVariable int month,
            @PathVariable int day,  @PathVariable int hour) {

        LocalDateTime hourBucket = LocalDateTime.of(year, month, day, hour, 0);
        log.info("GH Archive 수동 처리 요청: {}", hourBucket);
        adminAsyncService.processGhArchiveAsync(hourBucket);
        return ResponseEntity.accepted()
                .body(Map.of("status", "처리 시작됨", "hourBucket", hourBucket.toString()));
    }

    @GetMapping("/gharchive/status")
    public java.util.List<Map<String, Object>> ghArchiveStatus(@RequestParam(defaultValue = "50") int limit) {
        return ghArchiveHourService.unsuccessfulHours(limit);
    }

    @PostMapping("/trending/snapshot")
    public ResponseEntity<Map<String, Object>> snapshotTrending() {
        log.info("트렌딩 스냅샷 수동 트리거");
        adminAsyncService.snapshotNowAsync();
        return ResponseEntity.accepted().body(Map.of("status", "처리 시작됨"));
    }

    @PostMapping("/trending/daily/{date}")
    public ResponseEntity<Map<String, Object>> computeTrendingDay(@PathVariable java.time.LocalDate date) {
        log.info("일간 트렌딩 {} 수동 계산", date);
        adminAsyncService.computeTrendingDayAsync(date);
        return ResponseEntity.accepted().body(Map.of("status", "처리 시작됨", "date", date.toString()));
    }

    @PostMapping("/retention")
    public ResponseEntity<gitgalaxy.backend.service.RepoRetentionService.Result> retention(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(repoRetentionService.run(dryRun));
    }

    @PostMapping("/repo-embeddings/backfill")
    public ResponseEntity<Map<String, Object>> backfillRepoEmbeddings() {
        log.info("레포 임베딩 백필 수동 트리거");
        adminAsyncService.backfillRepoEmbeddingsAsync();
        return ResponseEntity.accepted().body(Map.of("status", "처리 시작됨"));
    }

    @PostMapping("/score")
    public ResponseEntity<Map<String, Object>> recalculateScores() {
        log.info("스코어 재계산 수동 트리거");
        adminAsyncService.recalculateScoresAsync();
        return ResponseEntity.accepted()
                .body(Map.of("status", "처리 시작됨"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception e) {
        log.error("AdminController 에러", e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage() != null ? e.getMessage() : ""));
    }
}
