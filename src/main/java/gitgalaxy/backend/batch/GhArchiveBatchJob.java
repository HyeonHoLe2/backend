package gitgalaxy.backend.batch;

import gitgalaxy.backend.service.GhArchiveHourService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@Slf4j
public class GhArchiveBatchJob {

    private final GhArchiveHourService ghArchiveHourService;

    @Scheduled(cron = "${gharchive.cron-expression:0 0 * * * *}")
    @SchedulerLock(name = "ghArchiveBatch", lockAtMostFor = "50m", lockAtLeastFor = "1m")
    public void run() {
        // GH Archive 파일명은 UTC 기준 → 서버 타임존(KST 등)과 무관하게 UTC로 직전 1시간 계산
        LocalDateTime prevHour = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(1).withMinute(0).withSecond(0).withNano(0);
        log.info("GhArchiveBatchJob 시작: hour={}", prevHour);
        ghArchiveHourService.runScheduled(prevHour);
    }
}
