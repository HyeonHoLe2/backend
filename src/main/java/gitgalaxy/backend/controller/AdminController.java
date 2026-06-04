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
 * POST /admin/trending                             → RSS 즉시 실행 + README 임베딩 (비동기)
 * POST /admin/score                                → 전체 repo 스코어 재계산 (비동기)
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AdminAsyncService adminAsyncService;

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

    @PostMapping("/trending")
    public ResponseEntity<Map<String, Object>> runTrending() {
        log.info("Trending RSS 수동 트리거");
        adminAsyncService.runTrendingAsync();
        return ResponseEntity.accepted()
                .body(Map.of("status", "처리 시작됨"));
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
