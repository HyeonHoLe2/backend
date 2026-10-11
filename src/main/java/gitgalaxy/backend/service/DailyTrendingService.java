package gitgalaxy.backend.service;

import gitgalaxy.backend.config.TrendingProperties;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.repository.TrendingRepository;
import gitgalaxy.backend.repository.TrendingRepository.StarDelta;
import gitgalaxy.backend.repository.TrendingRepository.TrendingRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일간 트렌딩 (UTC 하루 스타 증가량 = S1 − S0).
 * - 매시간: GH Archive 스타가 잡힌 후보 repo의 누적 스타 수를 스냅샷
 * - 하루 순위: S0 = 당일 00시 스냅샷(없으면 처음 발견 시점), S1 = 다음날 00시 스냅샷(진행 중이면 최신)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyTrendingService {

    private final TrendingProperties props;
    private final TrendingRepository trendingRepository;
    private final StarStatsService starStatsService;
    private final RepoRepository repoRepository;
    private final RepoOnboardService repoOnboardService;
    private final EmbeddingPipelineService embeddingPipelineService;
    private final GhArchiveService ghArchiveService;
    private final TrendEvidenceService trendEvidenceService;
    private final RepoRetentionService repoRetentionService;
    private final RepoEmbeddingService repoEmbeddingService;
    private final RepoSimilarityService repoSimilarityService;

    /**
     * 정각(UTC) 스냅샷. 현재 스타 수만 조회할 수 있으므로 정각에 실시간으로만 호출 (과거 시점 백필 불가).
     * 후보 = 직전 1시간이 속한 날의 GH Archive 스타 상위 + 그 전날 트렌딩 상위
     * → 00시 스냅샷은 전날 후보의 S1이자 다음날의 S0
     */
    public int snapshotAt(LocalDateTime snapshotHour) {
        LocalDate day = snapshotHour.minusHours(1).toLocalDate();

        Set<String> candidates = new LinkedHashSet<>(
                trendingRepository.findArchiveStarCandidates(day, props.getMinArchiveStars(), props.getCandidateLimit()));
        // 전날 상위 repo는 GH Archive에 안 잡혀도 계속 추적 (00시 S0 확보)
        trendingRepository.findTrending(day.minusDays(1), props.getFeaturedTop())
                .forEach(r -> candidates.add(r.fullName()));

        Map<String, Integer> counts = starStatsService.fetchStargazerCounts(new ArrayList<>(candidates));
        trendingRepository.upsertSnapshots(snapshotHour, counts);
        log.info("스타 스냅샷 {} UTC: 후보 {}개 중 {}개 저장", snapshotHour, candidates.size(), counts.size());
        return counts.size();
    }

    /** 순위 상위 onboardTop 중 아직 등록되지 않은 repo 온보딩 (메타·README 임베딩). 진행 중인 날은 매시간 새로 들어온 것만 */
    public int onboardMissing(List<TrendingRow> rows) {
        List<String> top = rows.stream().limit(props.getOnboardTop()).map(TrendingRow::fullName).toList();
        Set<String> tracked = repoRepository.findByFullNameIn(top).stream()
                .filter(Repo::isTracked).map(Repo::getFullName).collect(Collectors.toSet());
        List<String> toOnboard = top.stream().filter(n -> !tracked.contains(n)).toList();
        if (!toOnboard.isEmpty()) {
            embeddingPipelineService.embedAndStore(repoOnboardService.onboardAll(toOnboard));
            repoSimilarityService.computeFor(repoEmbeddingService.refresh(toOnboard));
        }
        return toOnboard.size();
    }

    /** 하루 순위 계산·저장. 다음날 00시 스냅샷이 아직 없으면 "지금까지" 순위 */
    public List<TrendingRow> computeDay(LocalDate day) {
        List<StarDelta> deltas = trendingRepository.findDayDeltas(day);

        List<StarDelta> ranked = deltas.stream()
                .filter(d -> d.s1() - d.s0() > 0)
                .sorted(Comparator.comparingInt((StarDelta d) -> d.s1() - d.s0()).reversed())
                .limit(props.getTopN())
                .toList();

        List<TrendingRow> rows = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            StarDelta d = ranked.get(i);
            rows.add(new TrendingRow(i + 1, d.fullName(), d.s1() - d.s0(), d.s1(), d.fullStart() ? "full" : "partial"));
        }
        trendingRepository.replaceTrending(day, rows);
        log.info("일간 트렌딩 {}: 후보 {}개 → 순위 {}개 저장 (1위 {})", day, deltas.size(), rows.size(),
                rows.isEmpty() ? "-" : rows.get(0).fullName() + " +" + rows.get(0).starsGained());
        return rows;
    }

    /**
     * 순위에 오른 repo들의 같은 날(UTC 00~24시) GH Archive 활동량 → 별 밝기.
     * 점수 가중치는 ScoreService 활동 점수와 동일 (스타는 증가량으로 따로 쓰므로 제외), 상한 없음.
     * 밝기 = log(1+점수) / log(1+그날 최고 점수) → 0~1 상대값
     */
    public int computeActivity(LocalDate day) {
        List<TrendingRow> rows = trendingRepository.findTrending(day, props.getTopN());
        if (rows.isEmpty()) return 0;
        Set<String> repos = rows.stream().map(TrendingRow::fullName).collect(Collectors.toSet());
        GhArchiveService.DayActivity day0 = ghArchiveService.countDayActivity(day, repos);

        Map<String, int[]> counts = new java.util.HashMap<>();
        Map<String, Double> scores = new java.util.HashMap<>();
        for (String repo : repos) {
            int[] c = day0.counts().getOrDefault(repo, new int[7]);
            counts.put(repo, c);
            scores.put(repo, c[GhArchiveService.A_PUSH] * 2.0 + c[GhArchiveService.A_PR_OPEN] * 4.0
                    + c[GhArchiveService.A_PR_MERGE] * 5.0 + c[GhArchiveService.A_IS_OPEN] * 2.0
                    + c[GhArchiveService.A_IS_CLOSE] * 1.0);
        }
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        Map<String, TrendingRepository.Activity> activities = new java.util.HashMap<>();
        for (String repo : repos) {
            int[] c = counts.get(repo);
            double score = scores.get(repo);
            double brightness = max <= 0 ? 0 : Math.log1p(score) / Math.log1p(max);
            activities.put(repo, new TrendingRepository.Activity(
                    c[GhArchiveService.A_PUSH], c[GhArchiveService.A_PR_OPEN], c[GhArchiveService.A_PR_MERGE],
                    c[GhArchiveService.A_IS_OPEN], c[GhArchiveService.A_IS_CLOSE], c[GhArchiveService.A_RELEASE],
                    c[GhArchiveService.A_FORK], score, brightness, day0.hours()));
        }
        trendingRepository.updateActivity(day, activities);
        log.info("일간 트렌딩 {} 활동량 저장: {}개 repo, 최고 점수 {}, GH Archive {}/24시간", day, repos.size(), max, day0.hours());
        return repos.size();
    }

    /** 확정된 하루(UTC 00시 경계를 지난 날) 마무리: 같은 날 활동량(별 밝기) + 상위 repo 온보딩 + 오래된 데이터 정리 */
    public void finalizeDay(LocalDate day) {
        List<TrendingRow> rows = computeDay(day);
        try {
            computeActivity(day);
        } catch (Exception e) {
            log.warn("일간 트렌딩 {} 활동량 집계 실패: {}", day, e.getMessage());
        }

        int onboarded = onboardMissing(rows);

        try {
            trendEvidenceService.generate(day);
        } catch (Exception e) {
            log.warn("일간 트렌딩 {} 근거 수집 실패: {}", day, e.getMessage());
        }

        try {
            repoRetentionService.run(false);
        } catch (Exception e) {
            log.warn("repo 보관 정책 실패: {}", e.getMessage());
        }

        int deleted = trendingRepository.deleteOlderThan(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(props.getRetentionDays()));
        log.info("일간 트렌딩 {} 확정: 신규 온보딩 {}개, 오래된 스타 기록 {}행 정리", day, onboarded, deleted);
    }
}
