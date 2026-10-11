package gitgalaxy.backend.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 일간 트렌딩 테이블 (repo_star_hourly / repo_star_snapshot / trending_daily). 시각은 모두 UTC */
@Repository
@RequiredArgsConstructor
public class TrendingRepository {

    private final JdbcTemplate jdbc;

    public record TrendingRow(int rank, String fullName, int starsGained, int stargazers, String method) {}

    /** 같은 날 활동량 (집계 전이면 null) */
    public record Activity(int pushes, int prsOpened, int prsMerged, int issuesOpened, int issuesClosed,
                           int releases, int forks, double score, double brightness, int hours) {}

    public record TrendingWithActivity(TrendingRow row, Activity activity) {}

    /** S0(시작 스냅샷)·S1(끝 스냅샷) 쌍. fullStart=false면 당일 00시가 아니라 처음 발견된 시점이 S0 */
    public record StarDelta(String fullName, int s0, int s1, boolean fullStart) {}

    // ─── GH Archive 시간별 스타 이벤트 ─────────────────────

    public void upsertHourlyStars(Map<String, Integer> stars, LocalDateTime bucket) {
        if (stars.isEmpty()) return;
        List<Map.Entry<String, Integer>> rows = List.copyOf(stars.entrySet());
        Timestamp ts = Timestamp.valueOf(bucket);
        jdbc.batchUpdate("""
                INSERT INTO repo_star_hourly (repo_full_name, bucket, stars) VALUES (?, ?, ?)
                ON CONFLICT (repo_full_name, bucket) DO UPDATE SET stars = EXCLUDED.stars
                """, rows, rows.size(), (ps, e) -> {
            ps.setString(1, e.getKey());
            ps.setTimestamp(2, ts);
            ps.setInt(3, e.getValue());
        });
    }

    /** 하루(UTC) 동안 지금까지 GH Archive 스타 합계 상위 후보 */
    public List<String> findArchiveStarCandidates(LocalDate day, int minStars, int limit) {
        return jdbc.queryForList("""
                SELECT repo_full_name FROM repo_star_hourly
                WHERE bucket >= ? AND bucket < ?
                GROUP BY repo_full_name
                HAVING sum(stars) >= ?
                ORDER BY sum(stars) DESC
                LIMIT ?
                """, String.class, start(day), start(day.plusDays(1)), minStars, limit);
    }

    // ─── 스냅샷 ──────────────────────────────────────────

    public void upsertSnapshots(LocalDateTime snapshotHour, Map<String, Integer> stargazers) {
        if (stargazers.isEmpty()) return;
        List<Map.Entry<String, Integer>> rows = List.copyOf(stargazers.entrySet());
        Timestamp hour = Timestamp.valueOf(snapshotHour);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        jdbc.batchUpdate("""
                INSERT INTO repo_star_snapshot (repo_full_name, snapshot_hour, stargazers, captured_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (repo_full_name, snapshot_hour) DO UPDATE
                    SET stargazers = EXCLUDED.stargazers, captured_at = EXCLUDED.captured_at
                """, rows, rows.size(), (ps, e) -> {
            ps.setString(1, e.getKey());
            ps.setTimestamp(2, hour);
            ps.setInt(3, e.getValue());
            ps.setTimestamp(4, now);
        });
    }

    /**
     * 하루(UTC) 증가량 재료.
     * S1 = 다음날 00시 스냅샷 (없으면 그날 마지막 스냅샷 = 진행 중인 날의 "지금까지")
     * S0 = 당일 00시 스냅샷 (없으면 그날 처음 찍힌 스냅샷)
     */
    public List<StarDelta> findDayDeltas(LocalDate day) {
        return jdbc.query("""
                WITH w AS (
                    SELECT repo_full_name, snapshot_hour, stargazers FROM repo_star_snapshot
                    WHERE snapshot_hour >= ? AND snapshot_hour <= ?
                ),
                first AS (SELECT DISTINCT ON (repo_full_name) repo_full_name, snapshot_hour, stargazers
                          FROM w ORDER BY repo_full_name, snapshot_hour ASC),
                last  AS (SELECT DISTINCT ON (repo_full_name) repo_full_name, snapshot_hour, stargazers
                          FROM w ORDER BY repo_full_name, snapshot_hour DESC)
                SELECT f.repo_full_name, f.stargazers AS s0, l.stargazers AS s1, f.snapshot_hour = ? AS full_start
                FROM first f JOIN last l USING (repo_full_name)
                WHERE l.snapshot_hour > f.snapshot_hour
                """, (rs, i) -> new StarDelta(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4)),
                start(day), start(day.plusDays(1)), start(day));
    }

    // ─── 순위 ────────────────────────────────────────────

    @Transactional
    public void replaceTrending(LocalDate day, List<TrendingRow> rows) {
        jdbc.update("DELETE FROM trending_daily WHERE trend_date = ?", Date.valueOf(day));
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        jdbc.batchUpdate("""
                INSERT INTO trending_daily (trend_date, rank, repo_full_name, stars_gained, stargazers, method, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, rows, rows.size(), (ps, r) -> {
            ps.setDate(1, Date.valueOf(day));
            ps.setInt(2, r.rank());
            ps.setString(3, r.fullName());
            ps.setInt(4, r.starsGained());
            ps.setInt(5, r.stargazers());
            ps.setString(6, r.method());
            ps.setTimestamp(7, now);
        });
    }

    public List<TrendingRow> findTrending(LocalDate day, int limit) {
        return jdbc.query("""
                SELECT rank, repo_full_name, stars_gained, stargazers, method FROM trending_daily
                WHERE trend_date = ? ORDER BY rank LIMIT ?
                """, (rs, i) -> new TrendingRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5)),
                Date.valueOf(day), limit);
    }

    public void updateActivity(LocalDate day, Map<String, Activity> activities) {
        if (activities.isEmpty()) return;
        List<Map.Entry<String, Activity>> rows = List.copyOf(activities.entrySet());
        jdbc.batchUpdate("""
                UPDATE trending_daily SET pushes = ?, prs_opened = ?, prs_merged = ?, issues_opened = ?, issues_closed = ?,
                       releases = ?, forks = ?, activity_score = ?, brightness = ?, activity_hours = ?
                WHERE trend_date = ? AND repo_full_name = ?
                """, rows, rows.size(), (ps, e) -> {
            Activity a = e.getValue();
            ps.setInt(1, a.pushes()); ps.setInt(2, a.prsOpened()); ps.setInt(3, a.prsMerged());
            ps.setInt(4, a.issuesOpened()); ps.setInt(5, a.issuesClosed()); ps.setInt(6, a.releases());
            ps.setInt(7, a.forks()); ps.setDouble(8, a.score()); ps.setDouble(9, a.brightness()); ps.setInt(10, a.hours());
            ps.setDate(11, Date.valueOf(day)); ps.setString(12, e.getKey());
        });
    }

    public List<TrendingWithActivity> findTrendingWithActivity(LocalDate day, int limit) {
        return jdbc.query("""
                SELECT rank, repo_full_name, stars_gained, stargazers, method,
                       pushes, prs_opened, prs_merged, issues_opened, issues_closed, releases, forks,
                       activity_score, brightness, activity_hours
                FROM trending_daily WHERE trend_date = ? ORDER BY rank LIMIT ?
                """, (rs, i) -> {
            TrendingRow row = new TrendingRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5));
            Activity a = rs.getObject("activity_score") == null ? null : new Activity(
                    rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                    rs.getDouble(13), rs.getDouble(14), rs.getInt(15));
            return new TrendingWithActivity(row, a);
        }, Date.valueOf(day), limit);
    }

    public record EvidenceRow(String evidenceJson, String summary, String trigger, String strength, String appeal) {}

    public void upsertEvidence(LocalDate day, String fullName, EvidenceRow e) {
        jdbc.update("""
                INSERT INTO trend_evidence (trend_date, repo_full_name, evidence, summary, trigger_text, strength, appeal, generated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trend_date, repo_full_name) DO UPDATE
                    SET evidence = EXCLUDED.evidence, summary = EXCLUDED.summary, trigger_text = EXCLUDED.trigger_text,
                        strength = EXCLUDED.strength, appeal = EXCLUDED.appeal, generated_at = EXCLUDED.generated_at
                """, Date.valueOf(day), fullName, e.evidenceJson(), e.summary(), e.trigger(), e.strength(), e.appeal(),
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** repo가 트렌딩에 오른 가장 최근 날의 순위·근거 (없으면 null) */
    public record LatestEvidence(LocalDate trendDate, int rank, int starsGained, EvidenceRow evidence) {}

    public LatestEvidence findLatestEvidence(String fullName) {
        List<LatestEvidence> rows = jdbc.query("""
                SELECT t.trend_date, t.rank, t.stars_gained, e.evidence, e.summary, e.trigger_text, e.strength, e.appeal
                  FROM trending_daily t
                  JOIN trend_evidence e ON e.trend_date = t.trend_date AND e.repo_full_name = t.repo_full_name
                 WHERE t.repo_full_name = ?
                 ORDER BY t.trend_date DESC LIMIT 1
                """, (rs, i) -> new LatestEvidence(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getInt(3),
                        new EvidenceRow(rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))),
                fullName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** repo별로 day 이전에 만든 가장 최근 appeal (재사용용) */
    public Map<String, String> findLatestAppeals(List<String> fullNames, LocalDate before) {
        Map<String, String> result = new java.util.HashMap<>();
        if (fullNames.isEmpty()) return result;
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT DISTINCT ON (repo_full_name) repo_full_name, appeal FROM trend_evidence
                     WHERE repo_full_name = ANY(?) AND trend_date < ? AND appeal IS NOT NULL
                     ORDER BY repo_full_name, trend_date DESC""");
            ps.setArray(1, con.createArrayOf("text", fullNames.toArray(String[]::new)));
            ps.setDate(2, Date.valueOf(before));
            return ps;
        }, rs -> { result.put(rs.getString(1), rs.getString(2)); });
        return result;
    }

    public Map<String, EvidenceRow> findEvidence(LocalDate day) {
        Map<String, EvidenceRow> result = new java.util.HashMap<>();
        jdbc.query("SELECT repo_full_name, evidence, summary, trigger_text, strength, appeal FROM trend_evidence WHERE trend_date = ?",
                rs -> { result.put(rs.getString(1), new EvidenceRow(rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6))); }, Date.valueOf(day));
        return result;
    }

    /** 하루(UTC) 안의 정각 스냅샷 (시간 오름차순): [snapshot_hour, stargazers] */
    public List<Object[]> findSnapshotsOfDay(String fullName, LocalDate day) {
        return jdbc.query("""
                SELECT snapshot_hour, stargazers FROM repo_star_snapshot
                WHERE repo_full_name = ? AND snapshot_hour >= ? AND snapshot_hour <= ? ORDER BY snapshot_hour
                """, (rs, i) -> new Object[]{rs.getTimestamp(1).toLocalDateTime(), rs.getInt(2)},
                fullName, start(day), start(day.plusDays(1)));
    }

    public LocalDate findLatestTrendDate() {
        Date d = jdbc.queryForObject("SELECT max(trend_date) FROM trending_daily", Date.class);
        return d == null ? null : d.toLocalDate();
    }

    /** 보관 기간이 지난 시간별 스타·스냅샷 정리 */
    public int deleteOlderThan(LocalDateTime cutoff) {
        Timestamp ts = Timestamp.valueOf(cutoff);
        return jdbc.update("DELETE FROM repo_star_hourly WHERE bucket < ?", ts)
             + jdbc.update("DELETE FROM repo_star_snapshot WHERE snapshot_hour < ?", ts);
    }

    private static Timestamp start(LocalDate day) {
        return Timestamp.valueOf(day.atStartOfDay());
    }
}
