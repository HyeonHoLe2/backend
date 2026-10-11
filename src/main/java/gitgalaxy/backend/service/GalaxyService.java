package gitgalaxy.backend.service;

import gitgalaxy.backend.model.GalaxyResponse;
import gitgalaxy.backend.model.GalaxyResponse.*;
import gitgalaxy.backend.repository.TrendingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 메인 은하 화면 데이터: 오늘 트렌딩 + 최근 트렌딩 + 배경 repo + 별자리 연결.
 * 매시간만 바뀌는 데이터라 5분 메모리 캐시 (배경 6천여 개 + 유사도 검색을 매 요청마다 하지 않도록).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GalaxyService {

    private static final long CACHE_MS = 5 * 60 * 1000;

    private final TrendingRepository trendingRepository;
    private final JdbcTemplate jdbc;
    private final gitgalaxy.backend.config.TrendingProperties trendingProperties;

    /** 이 유사도 이상만 연결 (레포 단위 벡터 기준) */
    @org.springframework.beans.factory.annotation.Value("${galaxy.similar-min:0.80}")
    private double similarMin;

    private record Cached(String key, long at, GalaxyResponse value) {}
    private volatile Cached cache;

    public GalaxyResponse get(LocalDate date, int days) {
        LocalDate day = date != null ? date : trendingRepository.findLatestTrendDate();
        if (day == null) return null;
        String key = day + "/" + days;
        Cached c = cache;
        if (c != null && c.key().equals(key) && System.currentTimeMillis() - c.at() < CACHE_MS) return c.value();

        long t0 = System.currentTimeMillis();
        List<TodayStar> today = today(day);
        Set<String> placed = new HashSet<>();
        today.forEach(s -> placed.add(s.fullName()));
        List<RecentStar> recent = recent(day, days, placed);
        recent.forEach(s -> placed.add(s.fullName()));
        List<BackgroundStar> background = background(placed);
        List<Link> links = new ArrayList<>(ownerLinks(today, recent));
        links.addAll(similarLinks(today, placed, background));

        GalaxyResponse res = new GalaxyResponse(day, day.isBefore(LocalDate.now(ZoneOffset.UTC)), days,
                today, recent, background, links);
        cache = new Cached(key, System.currentTimeMillis(), res);
        log.info("은하 화면 {}: 오늘 {} / 최근 {} / 배경 {} / 연결 {} ({}ms)", day, today.size(), recent.size(),
                background.size(), links.size(), System.currentTimeMillis() - t0);
        return res;
    }

    /** 확정 전이면 brightness가 없으므로 최근 48시간 활동 점수(0~100)를 0~1로 대체 */
    private List<TodayStar> today(LocalDate day) {
        return jdbc.query("""
                SELECT t.rank, t.repo_full_name, t.stars_gained, t.stargazers,
                       COALESCE(t.brightness, a.score / 100.0) AS brightness, r.language, r.description
                FROM trending_daily t
                LEFT JOIN repos r ON r.full_name = t.repo_full_name
                LEFT JOIN LATERAL (
                    SELECT brightness_score AS score FROM repo_time m
                    WHERE m.repo_owner = r.owner AND m.repo_name = r.name AND m.bucket >= now() - interval '48 hours'
                    ORDER BY m.bucket DESC LIMIT 1) a ON true
                WHERE t.trend_date = ? ORDER BY t.rank
                """, (rs, i) -> new TodayStar(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                (Double) rs.getObject(5), rs.getString(6), rs.getString(7)), Date.valueOf(day));
    }

    private List<RecentStar> recent(LocalDate day, int days, Set<String> exclude) {
        List<RecentStar> list = jdbc.query("""
                SELECT t.repo_full_name, ? - max(t.trend_date) AS days_ago, count(*) AS appearances,
                       min(t.rank) AS best_rank, sum(t.stars_gained) AS gained, max(r.language) AS language
                FROM trending_daily t LEFT JOIN repos r ON r.full_name = t.repo_full_name
                WHERE t.trend_date >= ? AND t.trend_date < ?
                GROUP BY t.repo_full_name
                ORDER BY days_ago, best_rank
                """, (rs, i) -> new RecentStar(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getString(6)),
                Date.valueOf(day), Date.valueOf(day.minusDays(days - 1L)), Date.valueOf(day));
        return list.stream().filter(s -> !exclude.contains(s.fullName())).toList();
    }

    private List<BackgroundStar> background(Set<String> exclude) {
        List<BackgroundStar> list = jdbc.query("""
                SELECT r.full_name, r.language, r.star_count, COALESCE(a.score, 0) / 100.0
                FROM repos r
                LEFT JOIN LATERAL (
                    SELECT brightness_score AS score FROM repo_time m
                    WHERE m.repo_owner = r.owner AND m.repo_name = r.name AND m.bucket >= now() - interval '48 hours'
                    ORDER BY m.bucket DESC LIMIT 1) a ON true
                WHERE r.tracked
                """, (rs, i) -> new BackgroundStar(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getDouble(4)));
        return list.stream().filter(s -> !exclude.contains(s.fullName())).toList();
    }

    /** 같은 소유자의 repo가 오늘·최근 트렌딩에 여러 개면 첫 repo를 중심으로 연결 */
    private List<Link> ownerLinks(List<TodayStar> today, List<RecentStar> recent) {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        today.forEach(s -> byOwner.computeIfAbsent(owner(s.fullName()), k -> new ArrayList<>()).add(s.fullName()));
        recent.forEach(s -> byOwner.computeIfAbsent(owner(s.fullName()), k -> new ArrayList<>()).add(s.fullName()));
        List<Link> links = new ArrayList<>();
        byOwner.values().stream().filter(v -> v.size() > 1)
                .forEach(v -> v.subList(1, v.size()).forEach(t -> links.add(new Link(v.get(0), t, "owner", null))));
        return links;
    }

    /**
     * 비슷한 레포 연결: 배치에서 미리 계산한 repo_similar(임계값 이상 쌍, 양방향)를 읽기만 함.
     * 오늘 트렌딩은 레포당 최대 3개, 배경·최근은 레포당 가장 비슷한 1개. 화면에 있는 별끼리만, 중복(A-B / B-A) 제거.
     */
    private List<Link> similarLinks(List<TodayStar> today, Set<String> placed, List<BackgroundStar> background) {
        Set<String> onScreen = new HashSet<>(placed);
        background.forEach(b -> onScreen.add(b.fullName()));
        Set<String> todayNames = new HashSet<>();
        today.forEach(t -> todayNames.add(t.fullName()));

        List<Object[]> rows = jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT repo_full_name, neighbor_full_name, similarity FROM repo_similar
                    WHERE repo_full_name = ANY(?) AND neighbor_full_name = ANY(?) AND similarity >= ?
                    ORDER BY repo_full_name, similarity DESC
                    """);
            var names = con.createArrayOf("text", onScreen.toArray(String[]::new));
            ps.setArray(1, names);
            ps.setArray(2, names);
            ps.setDouble(3, similarMin);
            return ps;
        }, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)});

        List<Link> links = new ArrayList<>();
        Set<String> seenPair = new HashSet<>();
        Map<String, Integer> perSource = new HashMap<>();
        for (Object[] r : rows) {
            String src = (String) r[0], dst = (String) r[1];
            if (!onScreen.contains(dst)) continue;
            int limit = todayNames.contains(src) ? 3 : 1;
            if (perSource.getOrDefault(src, 0) >= limit) continue;
            String pair = src.compareTo(dst) < 0 ? src + "|" + dst : dst + "|" + src;
            if (!seenPair.add(pair)) continue;
            perSource.merge(src, 1, Integer::sum);
            links.add(new Link(src, dst, "similar", Math.round((double) r[2] * 1000) / 1000.0));
        }
        return links;
    }

    private static String owner(String fullName) {
        return fullName.substring(0, fullName.indexOf('/'));
    }
}
