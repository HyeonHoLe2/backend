package gitgalaxy.backend.batch;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.service.EmbeddingPipelineService;
import gitgalaxy.backend.service.TrendingRssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrendingRssBatchJob {

    private final TrendingRssService trendingRssService;
    private final EmbeddingPipelineService embeddingPipelineService;

    // 매일 06:00, 18:00
    @Scheduled(cron = "${trending.rss.cron-expression:0 0 6,18 * * *}")
    public void run() {
        log.info("TrendingRssBatchJob 시작");
        try {
            // repo 발굴 + README 청크 추출
            List<ChunkDocument> chunks = trendingRssService.discoverAndUpsert();

            // README 임베딩 (OPENAI_API_KEY 미설정 시 자동 스킵)
            if (!chunks.isEmpty()) {
                log.info("TrendingRssBatchJob: README 청크 {}개 임베딩 시작", chunks.size());
                embeddingPipelineService.embedAndStore(chunks);
            }

            log.info("TrendingRssBatchJob 완료");
        } catch (Exception e) {
            log.error("TrendingRssBatchJob 실패", e);
        }
    }
}
