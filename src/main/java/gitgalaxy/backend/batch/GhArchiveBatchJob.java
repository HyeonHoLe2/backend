package gitgalaxy.backend.batch;

import gitgalaxy.backend.repository.RepoRepository;
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
    private final RepoRepository repoRepository;

    // 매 시각 10분 후 실행 (GH Archive 업로드 대기)
    @Scheduled(cron = "${gharchive.cron-expression:0 10 * * * *}")
    public void run() {
        LocalDateTime prevHour = LocalDateTime.now()
                .minusHours(1).withMinute(0).withSecond(0).withNano(0);
        log.info("GhArchiveBatchJob 시작: hour={}", prevHour);

        try {
            ghArchiveService.processHour(prevHour);
        } catch (Exception e) {
            log.error("GhArchiveBatchJob processHour 실패", e);
            return;
        }

        repoRepository.findByTrackedTrue().forEach(repo -> {
            try {
                scoreService.calculateAndSave(repo.getOwner(), repo.getName());
            } catch (Exception e) {
                log.warn("스코어 계산 실패 {}/{}: {}", repo.getOwner(), repo.getName(), e.getMessage());
            }
        });

        log.info("GhArchiveBatchJob 완료");
    }
}
