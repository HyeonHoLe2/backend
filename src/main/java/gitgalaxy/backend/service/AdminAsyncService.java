package gitgalaxy.backend.service;

import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAsyncService {

    private final GhArchiveHourService ghArchiveHourService;
    private final ScoreService scoreService;
    private final RepoRepository repoRepository;
    private final DailyTrendingService dailyTrendingService;
    private final TrendEvidenceService trendEvidenceService;
    private final RepoEmbeddingService repoEmbeddingService;
    private final RepoSimilarityService repoSimilarityService;

    /** 수동 처리도 상태 테이블을 거침 → 처리 중인 시간대와 겹치지 않고, 실패하면 재시도 대상에 오름 */
    @Async
    public void processGhArchiveAsync(LocalDateTime hourBucket) {
        try {
            ghArchiveHourService.process(hourBucket, false);
        } catch (Exception e) {
            log.error("GH Archive 수동 처리 실패: {}", e.getMessage(), e);
        }
    }

    /** 현재 시각 기준 스냅샷 + 오늘(UTC) 진행 순위 (수동 확인용) */
    @Async
    public void snapshotNowAsync() {
        try {
            LocalDateTime hour = LocalDateTime.now(java.time.ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0);
            dailyTrendingService.snapshotAt(hour);
            dailyTrendingService.computeDay(hour.toLocalDate());
        } catch (Exception e) {
            log.error("수동 스냅샷 실패: {}", e.getMessage(), e);
        }
    }

    /** 특정 날짜 순위 다시 계산 + 같은 날 활동량(별 밝기) 집계 */
    @Async
    public void computeTrendingDayAsync(java.time.LocalDate day) {
        try {
            dailyTrendingService.computeDay(day);
            dailyTrendingService.computeActivity(day);
            trendEvidenceService.generate(day);
        } catch (Exception e) {
            log.error("일간 트렌딩 {} 계산 실패: {}", day, e.getMessage(), e);
        }
    }

    /** 레포 임베딩이 없는 추적 repo 일괄 생성 → 전체 유사 레포 재계산 */
    @Async
    public void backfillRepoEmbeddingsAsync() {
        try {
            long t0 = System.currentTimeMillis();
            int embedded = repoEmbeddingService.backfillMissing();
            int similar = repoSimilarityService.computeAll();
            log.info("레포 임베딩 백필 완료: 임베딩 {}개, 유사 레포 계산 {}개 ({}s)", embedded, similar,
                    (System.currentTimeMillis() - t0) / 1000);
        } catch (Exception e) {
            log.error("레포 임베딩 백필 실패: {}", e.getMessage(), e);
        }
    }

    @Async
    public void recalculateScoresAsync() {
        try {
            log.info("스코어 재계산 비동기 처리 시작");
            long[] count = {0};
            repoRepository.findByTrackedTrue().forEach(repo -> {
                scoreService.calculateAndSave(repo.getOwner(), repo.getName(), null);
                count[0]++;
            });
            log.info("스코어 재계산 비동기 처리 완료: {}개", count[0]);
        } catch (Exception e) {
            log.error("스코어 재계산 비동기 처리 실패: {}", e.getMessage(), e);
        }
    }
}
