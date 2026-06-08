package gitgalaxy.backend.batch;

import gitgalaxy.backend.model.GhArchiveResult;
import gitgalaxy.backend.service.EmbeddingPipelineService;
import gitgalaxy.backend.service.GhArchiveService;
import gitgalaxy.backend.service.ScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GhArchiveBatchJob {

    private final GhArchiveService ghArchiveService;
    private final ScoreService scoreService;
    private final EmbeddingPipelineService embeddingPipelineService;

    @Scheduled(cron = "${gharchive.cron-expression:0 0 * * * *}")
    public void run() {
        LocalDateTime prevHour = LocalDateTime.now()
                .minusHours(1).withMinute(0).withSecond(0).withNano(0);
        log.info("GhArchiveBatchJob 시작: hour={}", prevHour);

        GhArchiveResult result;
        try {
            result = ghArchiveService.processHour(prevHour);
        } catch (Exception e) {
            log.error("GhArchiveBatchJob processHour 실패", e);
            return;
        }

        if (!result.chunks().isEmpty()) {
            log.info("GhArchiveBatchJob: {}개 청크 임베딩", result.chunks().size());
            try {
                embeddingPipelineService.embedAndStore(result.chunks());
            } catch (Exception e) {
                log.error("임베딩 실패", e);
            }
        }

        // 이벤트가 발생한 레포만 배치 스코어 계산
        List<String> affectedList = result.affectedRepos().stream()
                .filter(f -> f.contains("/")).toList();
        try {
            scoreService.calculateAndSaveBatch(affectedList, prevHour);
        } catch (Exception e) {
            log.warn("배치 스코어 계산 실패: {}", e.getMessage());
        }

        log.info("GhArchiveBatchJob 완료: 스코어 계산 레포={}개", affectedList.size());
    }
}
