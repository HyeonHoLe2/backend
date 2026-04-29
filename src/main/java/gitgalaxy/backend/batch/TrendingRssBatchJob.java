package gitgalaxy.backend.batch;

import gitgalaxy.backend.service.TrendingRssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrendingRssBatchJob {

    private final TrendingRssService trendingRssService;

    // 매일 오전 6시, 오후 6시 (12시간 간격)
    @Scheduled(cron = "${trending.rss.cron-expression:0 0 6,18 * * *}")
    public void run() {
        log.info("TrendingRssBatchJob 시작");
        try {
            int count = trendingRssService.discoverAndUpsert();
            log.info("TrendingRssBatchJob 완료: {}개 repo upsert", count);
        } catch (Exception e) {
            log.error("TrendingRssBatchJob 실패", e);
        }
    }
}
