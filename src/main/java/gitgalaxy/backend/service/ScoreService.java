package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.RepoHourlyMetrics;
import gitgalaxy.backend.entity.RepoScore;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoreService {

    private final RepoHourlyMetricsRepository metricsRepository;
    private final RepoScoreRepository scoreRepository;

    /**
     * 단일 repo 스코어 계산 및 저장.
     * activityScore: 최근 24h 이벤트 가중합 (0~100)
     * healthScore: 최근 30d 시간당 평균 (0~100)
     */
    public RepoScore calculateAndSave(String owner, String name) {
        LocalDateTime now = LocalDateTime.now();

        double activityScore = calcActivityScore(owner, name, now.minusHours(24), now);
        double healthScore   = calcHealthScore(owner, name, now.minusDays(30), now);

        RepoScore score = RepoScore.builder()
                .repoOwner(owner)
                .repoName(name)
                .scoredAt(now)
                .activityScore(activityScore)
                .healthScore(healthScore)
                .build();

        log.debug("score {}/{}: activity={:.1f} health={:.1f}", owner, name, activityScore, healthScore);
        return scoreRepository.save(score);
    }

    // watch×3 + push×2 + pr×4 + issue×2, 24h 합산, max=1000 → 100점
    private double calcActivityScore(String owner, String name, LocalDateTime from, LocalDateTime to) {
        List<RepoHourlyMetrics> rows = metricsRepository
                .findByRepoOwnerAndRepoNameAndHourBucketBetweenOrderByHourBucketAsc(owner, name, from, to);
        if (rows.isEmpty()) return 0.0;

        double raw = rows.stream().mapToDouble(r ->
                r.getWatchCount() * 3.0 +
                r.getPushCount()  * 2.0 +
                r.getPrCount()    * 4.0 +
                r.getIssueCount() * 2.0
        ).sum();

        return Math.min(100.0, raw / 10.0);
    }

    // 30d 시간당 평균 총 이벤트, 시간당 평균 5개 = 100점
    private double calcHealthScore(String owner, String name, LocalDateTime from, LocalDateTime to) {
        List<RepoHourlyMetrics> rows = metricsRepository
                .findByRepoOwnerAndRepoNameAndHourBucketBetweenOrderByHourBucketAsc(owner, name, from, to);
        if (rows.isEmpty()) return 0.0;

        double hourlyAvg = rows.stream().mapToDouble(r ->
                r.getWatchCount() + r.getPushCount() + r.getPrCount() + r.getIssueCount()
        ).average().orElse(0.0);

        return Math.min(100.0, hourlyAvg * 20.0);
    }
}
