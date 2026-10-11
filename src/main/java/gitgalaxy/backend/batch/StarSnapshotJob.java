package gitgalaxy.backend.batch;

import gitgalaxy.backend.service.DailyTrendingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 매시 정각(UTC) 후보 repo의 GitHub 누적 스타 수 스냅샷 → 일간 트렌딩 S0/S1.
 * GH Archive 수집 배치(매시 10분)는 수 분~수십 분 걸리므로 정각 오차를 없애려고 분리.
 * (spring.task.scheduling.pool.size=2 → 수집 배치가 길어져도 막히지 않음)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StarSnapshotJob {

    private final DailyTrendingService dailyTrendingService;

    @Scheduled(cron = "${trending.daily.snapshot-cron:0 0 * * * *}", zone = "UTC")
    @SchedulerLock(name = "starSnapshot", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        // 스케줄러가 정각보다 수 ms 일찍 깨는 경우가 있어 내림 대신 가장 가까운 정각으로 반올림 (10:59:59.99 → 11:00)
        LocalDateTime hour = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.HOURS);
        try {
            dailyTrendingService.snapshotAt(hour);
        } catch (Exception e) {
            log.warn("스타 스냅샷 {} UTC 실패: {}", hour, e.getMessage());
        }
    }
}
