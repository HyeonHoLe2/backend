package gitgalaxy.backend.batch;

import gitgalaxy.backend.config.GithubCollectorProperties;
import gitgalaxy.backend.model.RepoResult;
import gitgalaxy.backend.service.RepoCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 스케줄러 기반 Batch Job 진입점.
 *
 * - cron 표현식은 application.properties의 github.collector.cron-expression 으로 설정
 * - 기본값: "0 0 * * * *" (매 시각 정각)
 * - 멀티스레드 없이 단일 스레드로 순차 실행 (추후 @Async 또는 ThreadPoolTaskExecutor로 확장 가능)
 *
 * cron 예시:
 *   "0 0 * * * *"       1시간마다
 *   "0 0 9 * * *"       매일 오전 9시
 *   "0 0 0/6 * * *"     6시간마다
 *   "0 0/30 * * * *"    30분마다
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepoBatchJob {

    private final RepoCollectorService repoCollectorService;
    private final GithubCollectorProperties props;

    // TrendingRssBatchJob이 README 수집을 담당하므로 기본 비활성화 (-).
    // 필요 시 github.collector.cron-expression 프로퍼티로 활성화.
    @Scheduled(cron = "${github.collector.cron-expression:-}")
    public void run() {
        // GitHub Token 미설정 시 조기 종료
        if (props.getToken() == null || props.getToken().isBlank()) {
            log.error("GITHUB_TOKEN 환경변수가 설정되지 않았습니다. Batch Job을 건너뜁니다.");
            return;
        }

        log.info("╔═══════════════════════════════════════╗");
        log.info("║   GitHub Doc Collector Batch 시작      ║");
        log.info("╚═══════════════════════════════════════╝");
        long jobStart = System.currentTimeMillis();

        try {
            List<RepoResult> results = repoCollectorService.runBatch();

            long success = results.stream().filter(r -> "success".equals(r.getStatus())).count();
            long failed  = results.stream().filter(r -> "failed".equals(r.getStatus())).count();
            long skipped = results.stream().filter(r -> "skipped".equals(r.getStatus())).count();
            long elapsed = System.currentTimeMillis() - jobStart;

            log.info("╔═══════════════════════════════════════╗");
            log.info("║   Batch 완료                           ║");
            log.info("║   total={} success={} failed={} skipped={}", results.size(), success, failed, skipped);
            log.info("║   elapsed={}ms", elapsed);
            log.info("╚═══════════════════════════════════════╝");

        } catch (Exception e) {
            log.error("Batch Job에서 예상치 못한 오류 발생", e);
        }
    }
}
