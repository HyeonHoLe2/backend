package gitgalaxy.backend.service;

import gitgalaxy.backend.config.GhArchiveProperties;
import gitgalaxy.backend.metrics.CollectorMetrics;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.model.GhArchiveResult;
import gitgalaxy.backend.repository.GhArchiveHourStatusRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * GH Archive 시간대 1개 처리 파이프라인 + 처리 상태 기록.
 * 스케줄 배치(직전 시간대·재처리)와 관리 API 수동 실행이 모두 이 경로를 사용.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GhArchiveHourService {

    private final GhArchiveService ghArchiveService;
    private final RepoOnboardService repoOnboardService;
    private final RepoSimilarityService repoSimilarityService;
    private final EmbeddingPipelineService embeddingPipelineService;
    private final ScoreService scoreService;
    private final DailyTrendingService dailyTrendingService;
    private final GhArchiveHourStatusRepository statusRepository;
    private final GhArchiveProperties props;
    private final GithubClient githubClient;
    private final gitgalaxy.backend.config.GithubCollectorProperties githubProps;
    private final CollectorMetrics collectorMetrics;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void registerGauges() {
        for (String status : List.of("failed", "dead")) {
            Gauge.builder("gitgalaxy.gharchive.hours", statusRepository, r -> r.countByStatus(status))
                    .description("GH Archive 시간대 처리 상태별 개수 (failed = 재시도 대기, dead = 재시도 포기)")
                    .tag("status", status).register(meterRegistry);
        }
    }

    /** 매시 배치: 기록 없는 시간대 등록 → 직전 시간대 처리 → 재시도 시각이 된 실패 시간대 이어서 처리 */
    public void runScheduled(LocalDateTime prevHour) {
        int missing = statusRepository.registerMissing(prevHour.minusHours(props.getMissingLookbackHours()), prevHour);
        if (missing > 0) log.warn("처리 기록 없는 시간대 {}개 → 재처리 대상 등록", missing);

        process(prevHour, true);

        for (LocalDateTime hour : statusRepository.findDue(props.getRetryPerRun())) {
            if (!hasBudgetForRetry()) break;
            log.info("실패 시간대 재처리: {}", hour);
            process(hour, false);
        }
    }

    /**
     * 재처리는 GitHub REST 한도가 넉넉할 때만 (시간대 1개 커밋 조회 최대치 + 다음 메타 갱신 몫 + 여유분).
     * 부족하면 다음 시간으로 미룸 → 정시 처리·메타 갱신이 항상 먼저. 남은 요청을 아직 모르면(-1) 진행.
     */
    private boolean hasBudgetForRetry() {
        long remaining = githubClient.getRateLimitRemaining();
        long needed = (long) props.getMaxCommitFetchPerHour() + githubProps.getMetaRefreshBatchSize()
                + githubProps.getRateLimitReserve();
        if (remaining >= 0 && remaining < needed) {
            log.info("GitHub 남은 요청 {} < 재처리 필요량 {} (커밋 조회 {} + 메타 갱신 {} + 여유분 {}) → 재처리는 다음 시간으로",
                    remaining, needed, props.getMaxCommitFetchPerHour(), githubProps.getMetaRefreshBatchSize(),
                    githubProps.getRateLimitReserve());
            return false;
        }
        return true;
    }

    /**
     * 시간대 1개 처리. 처리 권한을 얻지 못하면(다른 실행이 처리 중) 건너뜀.
     * @param updateDaily 일간 트렌딩 갱신 여부 — 직전 시간대만 true (재처리는 시간별 데이터 복구만)
     * @return 기록된 상태 (success | failed | dead | skipped)
     */
    public String process(LocalDateTime hour, boolean updateDaily) {
        if (!statusRepository.claim(hour)) {
            log.info("GH Archive {}: 다른 실행이 처리 중 → 건너뜀", hour);
            return "skipped";
        }
        long start = System.nanoTime();
        collectorMetrics.startRun();
        String status;
        try {
            GhArchiveResult result = ghArchiveService.processHour(hour);
            runDownstream(hour, result, updateDaily);
            long ms = (System.nanoTime() - start) / 1_000_000;
            if ("success".equals(result.status())) {
                statusRepository.markSuccess(hour, ms);
                status = "success";
            } else {
                status = statusRepository.markFailed(hour, "GH Archive 파일 " + result.status(), ms, props.getRetryMaxAttempts());
            }
            collectorMetrics.ghArchiveHour(result.status(), start);
        } catch (Exception e) {
            log.error("GH Archive {} 처리 실패", hour, e);
            status = statusRepository.markFailed(hour, String.valueOf(e.getMessage()),
                    (System.nanoTime() - start) / 1_000_000, props.getRetryMaxAttempts());
            collectorMetrics.ghArchiveHour("failed", start);
        }
        if ("dead".equals(status)) {
            log.error("GH Archive {}: 재시도 {}회 모두 실패 → dead (수동 확인 필요)", hour, props.getRetryMaxAttempts());
        }
        log.info("GH Archive {} 처리 {} ({}s) 단계별: {}", hour, status,
                (System.nanoTime() - start) / 1_000_000_000, collectorMetrics.endRun());
        return status;
    }

    /** 온보딩·임베딩·점수·일간 트렌딩. 단계별 실패는 해당 단계만 건너뜀 (시간대 상태에는 영향 없음) */
    private void runDownstream(LocalDateTime hour, GhArchiveResult result, boolean updateDaily) {
        if (!result.discoveredRepos().isEmpty()) {
            // 활동 레포는 은하 배경 별 → 메타만 가볍게 등록 (README 임베딩 없음)
            List<String> registered = repoOnboardService.registerLight(result.discoveredRepos());
            collectorMetrics.step("6.유사 레포 계산", () -> repoSimilarityService.computeFor(registered));
        }

        List<ChunkDocument> chunks = new ArrayList<>(result.chunks());
        if (!chunks.isEmpty()) {
            try {
                collectorMetrics.step("7.청크 임베딩 전체", () -> embeddingPipelineService.embedAndStore(chunks));
            } catch (Exception e) {
                log.error("임베딩 실패", e);
            }
        }

        // 이벤트가 발생한 레포만 배치 스코어 계산
        List<String> affected = result.affectedRepos().stream().filter(f -> f.contains("/")).toList();
        try {
            collectorMetrics.step("8.점수 계산", () -> scoreService.calculateAndSaveBatch(affected, hour));
        } catch (Exception e) {
            log.warn("배치 스코어 계산 실패: {}", e.getMessage());
        }

        if (!updateDaily) return;
        // 일간 트렌딩: 진행 중인 날 순위 갱신, UTC 23시 시간대를 처리했으면 그날 확정 (활동량·온보딩 포함)
        try {
            if (hour.getHour() == 23) {
                collectorMetrics.step("9.하루 확정", () -> dailyTrendingService.finalizeDay(hour.toLocalDate()));
            } else {
                // 진행 중인 날: 순위 갱신 + 새로 순위권에 든 repo 등록 (은하 화면 언어·설명용)
                collectorMetrics.step("9.순위 갱신·트렌딩 등록",
                        () -> dailyTrendingService.onboardMissing(dailyTrendingService.computeDay(hour.toLocalDate())));
            }
        } catch (Exception e) {
            log.warn("일간 트렌딩 갱신 실패: {}", e.getMessage());
        }
    }

    public List<java.util.Map<String, Object>> unsuccessfulHours(int limit) {
        return statusRepository.findUnsuccessful(limit);
    }
}
