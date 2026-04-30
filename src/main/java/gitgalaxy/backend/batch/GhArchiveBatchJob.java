package gitgalaxy.backend.batch;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.RepoRepository;
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
    private final RepoRepository repoRepository;
    private final EmbeddingPipelineService embeddingPipelineService;

    // 매 시각 10분 후 (GH Archive 업로드 대기)
    @Scheduled(cron = "${gharchive.cron-expression:0 10 * * * *}")
    public void run() {
        LocalDateTime prevHour = LocalDateTime.now()
                .minusHours(1).withMinute(0).withSecond(0).withNano(0);
        log.info("GhArchiveBatchJob 시작: hour={}", prevHour);

        // GH Archive 처리: repo_time 메트릭 upsert + 텍스트 청크 추출
        List<ChunkDocument> chunks;
        try {
            chunks = ghArchiveService.processHour(prevHour);
        } catch (Exception e) {
            log.error("GhArchiveBatchJob processHour 실패", e);
            return;
        }

        // commit/PR/issue 텍스트 임베딩
        if (!chunks.isEmpty()) {
            log.info("GhArchiveBatchJob: {}개 청크 임베딩", chunks.size());
            try {
                embeddingPipelineService.embedAndStore(chunks);
            } catch (Exception e) {
                log.error("임베딩 실패", e);
            }
        }

        // repo_time 해당 bucket 행에 스코어 UPDATE
        repoRepository.findByTrackedTrue().forEach(repo -> {
            try {
                scoreService.calculateAndSave(repo.getOwner(), repo.getName(), prevHour);
            } catch (Exception e) {
                log.warn("스코어 계산 실패 {}/{}: {}", repo.getOwner(), repo.getName(), e.getMessage());
            }
        });

        log.info("GhArchiveBatchJob 완료");
    }
}
