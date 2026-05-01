package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.RepoHourlyMetrics;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoreService {

    private final RepoHourlyMetricsRepository metricsRepository;

    /**
     * 단일 repo 스코어 계산 → repo_time 테이블 해당 bucket 행에 UPDATE.
     * GhArchiveBatchJob이 upsert 직후 호출.
     */
    public void calculateAndSave(String owner, String name, LocalDateTime bucket) {
        LocalDateTime now = bucket;

        double activeScore     = calcActivityScore(owner, name, now.minusHours(24), now);
        double healthScore     = calcHealthScore(owner, name, now.minusDays(30), now);
        double brightnessScore = Math.min(100.0, activeScore * 0.7 + healthScore * 0.3);
        double sizeScore       = healthScore;

        metricsRepository.updateScores(owner, name, Timestamp.valueOf(bucket), activeScore, healthScore, brightnessScore, sizeScore);
        log.debug("score {}/{} @{}: active={:.1f} health={:.1f}", owner, name, bucket, activeScore, healthScore);
    }

    // ─── 스코어 계산 ────────────────────────────────────

    // watch×3 + commit×2 + prCreated×4 + prMerged×5 + issueOpened×2 → 합산, max=1000 → 100점
    private double calcActivityScore(String owner, String name, LocalDateTime from, LocalDateTime to) {
        List<RepoHourlyMetrics> rows = metricsRepository
                .findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(owner, name, from, to);
        if (rows.isEmpty()) return 0.0;

        double raw = rows.stream().mapToDouble(r ->
                r.getWatch()        * 3.0 +
                r.getCommitCount()  * 2.0 +
                r.getPrCreated()    * 4.0 +
                r.getPrMerged()     * 5.0 +
                r.getIssueOpened()  * 2.0 +
                r.getIssueClosed()  * 1.0
        ).sum();

        return Math.min(100.0, raw / 10.0);
    }

    // 30일 시간당 평균 총 이벤트 → 시간당 5개 = 100점
    private double calcHealthScore(String owner, String name, LocalDateTime from, LocalDateTime to) {
        List<RepoHourlyMetrics> rows = metricsRepository
                .findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(owner, name, from, to);
        if (rows.isEmpty()) return 0.0;

        double hourlyAvg = rows.stream().mapToDouble(r ->
                r.getWatch() + r.getCommitCount() + r.getPrCreated() +
                r.getPrMerged() + r.getIssueOpened() + r.getIssueClosed()
        ).average().orElse(0.0);

        return Math.min(100.0, hourlyAvg * 20.0);
    }
}
