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

        // 이벤트가 발생한 레포만 스코어 계산
        int scored = 0;
        for (String fullName : result.affectedRepos()) {
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2) continue;
            try {
                scoreService.calculateAndSave(parts[0], parts[1], prevHour);
                scored++;
            } catch (Exception e) {
                log.warn("스코어 계산 실패 {}: {}", fullName, e.getMessage());
            }
        }

        log.info("GhArchiveBatchJob 완료: 스코어 계산 레포={}개 (전체 추적 레포 중 이벤트 발생한 것만)", scored);
    }
}
