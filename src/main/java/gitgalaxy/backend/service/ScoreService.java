package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.RepoHourlyMetrics;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoMetricsBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoreService {

    private final RepoHourlyMetricsRepository metricsRepository;
    private final RepoMetricsBatchRepository batchRepository;
    private final TrendReasonService trendReasonService;

    /**
     * 단일 repo 스코어 계산 → repo_time 테이블 해당 bucket 행에 UPDATE.
     * GhArchiveBatchJob이 upsert 직후 호출.
     */
    public void calculateAndSave(String owner, String name, LocalDateTime bucket) {
        LocalDateTime targetBucket = bucket;

        if (targetBucket == null) {
            targetBucket = metricsRepository
                    .findTopByRepoOwnerAndRepoNameOrderByBucketDesc(owner, name)
                    .map(RepoHourlyMetrics::getBucket)
                    .orElse(null);
        }

        if (targetBucket == null) {
            log.debug("score {}/{}: repo_time 데이터 없음 — 스킵", owner, name);
            return;
        }

        // 이전 brightnessScore 조회
        Double prevBrightnessScore = metricsRepository
                .findTopByRepoOwnerAndRepoNameOrderByBucketDesc(owner, name)
                .map(RepoHourlyMetrics::getBrightnessScore)
                .orElse(null);

        double activeScore     = calcActivityScore(owner, name, targetBucket.minusHours(24), targetBucket);
        double healthScore     = calcHealthScore(owner, name, targetBucket.minusDays(7), targetBucket);
        double sizeScore       = calcHealthScore(owner, name, targetBucket.minusDays(30), targetBucket);
        double brightnessScore = Math.min(100.0, activeScore * 0.85 + healthScore * 0.15);

        metricsRepository.updateScores(owner, name, Timestamp.valueOf(targetBucket), activeScore, healthScore, brightnessScore, sizeScore);
        log.debug("score {}/{} @{}: active={} health={}", owner, name, targetBucket, activeScore, healthScore);

        // ±20% 이상 변화 시 Redis 캐시 무효화 → 다음 요청 시 LLM 재생성
        if (prevBrightnessScore != null && prevBrightnessScore > 0.1) {
            double changeRate = Math.abs(brightnessScore - prevBrightnessScore) / prevBrightnessScore * 100.0;
            if (changeRate >= 20.0) {
                log.info("score 변화 {}% → trend reason 재생성: {}/{}", String.format("%.1f", changeRate), owner, name);
                trendReasonService.regenerate(owner, name);
            }
        }
    }

    /**
     * 여러 repo 스코어 배치 계산 → DB 왕복 3번으로 처리.
     * GhArchiveBatchJob이 affectedRepos 전달 시 호출.
     */
    public void calculateAndSaveBatch(List<String> fullNames, LocalDateTime bucket) {
        if (fullNames.isEmpty()) return;

        // ① prevBrightnessScore 한방에 조회
        Map<String, Double> prevBrightness = batchRepository.findLatestForRepos(fullNames)
                .stream()
                .collect(Collectors.toMap(
                        r -> r.getRepoOwner() + "/" + r.getRepoName(),
                        r -> r.getBrightnessScore() != null ? r.getBrightnessScore() : 0.0
                ));

        // ② 30일치 데이터 한방에 조회 (24h / 7d / 30d 모두 커버)
        List<RepoHourlyMetrics> allRows = batchRepository.findAllForReposInRange(
                fullNames, bucket.minusDays(30), bucket);

        Map<String, List<RepoHourlyMetrics>> byRepo = allRows.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRepoOwner() + "/" + r.getRepoName()
                ));

        // ③ 스코어 계산
        List<RepoMetricsBatchRepository.ScoreUpdate> updates = new ArrayList<>();

        for (String fullName : fullNames) {
            List<RepoHourlyMetrics> rows = byRepo.getOrDefault(fullName, List.of());
            if (rows.isEmpty()) continue;

            String[] parts = fullName.split("/", 2);
            double active     = calcActivityScore(rows, bucket.minusHours(24), bucket);
            double health     = calcHealthScore(rows, bucket.minusDays(7), bucket);
            double size       = calcHealthScore(rows, bucket.minusDays(30), bucket);
            double brightness = Math.min(100.0, active * 0.85 + health * 0.15);

            updates.add(new RepoMetricsBatchRepository.ScoreUpdate(
                    parts[0], parts[1], bucket, active, health, brightness, size));

            double prev = prevBrightness.getOrDefault(fullName, 0.0);
            if (prev > 0.1) {
                double changeRate = Math.abs(brightness - prev) / prev * 100.0;
                if (changeRate >= 20.0) {
                    trendReasonService.regenerate(parts[0], parts[1]);
                }
            }
        }

        // ④ UPDATE 한방에
        batchRepository.batchUpdateScores(updates);
        log.debug("배치 스코어 계산 완료: {}개 레포", updates.size());
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

    // 배치용 — 이미 로딩된 rows에서 시간 범위 필터 후 계산 (DB 조회 없음)
    private double calcActivityScore(List<RepoHourlyMetrics> rows, LocalDateTime from, LocalDateTime to) {
        double raw = rows.stream()
                .filter(r -> !r.getBucket().isBefore(from) && !r.getBucket().isAfter(to))
                .mapToDouble(r ->
                        r.getWatch()       * 3.0 +
                        r.getCommitCount() * 2.0 +
                        r.getPrCreated()   * 4.0 +
                        r.getPrMerged()    * 5.0 +
                        r.getIssueOpened() * 2.0 +
                        r.getIssueClosed() * 1.0
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

    // 배치용
    private double calcHealthScore(List<RepoHourlyMetrics> rows, LocalDateTime from, LocalDateTime to) {
        return rows.stream()
                .filter(r -> !r.getBucket().isBefore(from) && !r.getBucket().isAfter(to))
                .mapToDouble(r ->
                        r.getWatch() + r.getCommitCount() + r.getPrCreated() +
                        r.getPrMerged() + r.getIssueOpened() + r.getIssueClosed()
                ).average()
                .stream()
                .map(avg -> Math.min(100.0, avg * 20.0))
                .findFirst()
                .orElse(0.0);
    }
}
