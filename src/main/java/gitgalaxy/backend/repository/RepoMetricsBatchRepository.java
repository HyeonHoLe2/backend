package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.RepoHourlyMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class RepoMetricsBatchRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<RepoHourlyMetrics> MAPPER = (rs, rowNum) -> {
        RepoHourlyMetrics m = new RepoHourlyMetrics();
        m.setId(rs.getLong("id"));
        m.setRepoOwner(rs.getString("repo_owner"));
        m.setRepoName(rs.getString("repo_name"));
        Timestamp bucket = rs.getTimestamp("bucket");
        m.setBucket(bucket != null ? bucket.toLocalDateTime() : null);
        m.setWatch(rs.getInt("watch"));
        m.setCommitCount(rs.getInt("commit_count"));
        m.setPrCreated(rs.getInt("pr_created"));
        m.setPrMerged(rs.getInt("pr_merged"));
        m.setIssueOpened(rs.getInt("issue_opened"));
        m.setIssueClosed(rs.getInt("issue_closed"));
        m.setStarCount(rs.getInt("star_count"));
        m.setReleaseCount(rs.getInt("release_count"));
        m.setActiveScore(rs.getObject("active_score", Double.class));
        m.setHealthScore(rs.getObject("health_score", Double.class));
        m.setBrightnessScore(rs.getObject("brightness_score", Double.class));
        m.setSizeScore(rs.getObject("size_score", Double.class));
        return m;
    };

    public record ScoreUpdate(String owner, String name, LocalDateTime bucket,
                              double activeScore, double healthScore,
                              double brightnessScore, double sizeScore) {}

    /** 여러 레포의 최신 행 한방에 (prevBrightnessScore 조회용) */
    public List<RepoHourlyMetrics> findLatestForRepos(List<String> fullNames) {
        if (fullNames.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT DISTINCT ON (repo_owner, repo_name) *
                    FROM repo_time
                    WHERE repo_owner || '/' || repo_name = ANY(?)
                    ORDER BY repo_owner, repo_name, bucket DESC
                    """);
            ps.setArray(1, con.createArrayOf("text", fullNames.toArray(String[]::new)));
            return ps;
        }, MAPPER);
    }

    /** 여러 레포의 기간 내 전체 데이터 한방에 (스코어 계산 원재료) */
    public List<RepoHourlyMetrics> findAllForReposInRange(List<String> fullNames, LocalDateTime from, LocalDateTime to) {
        if (fullNames.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT * FROM repo_time
                    WHERE repo_owner || '/' || repo_name = ANY(?)
                      AND bucket BETWEEN ? AND ?
                    ORDER BY repo_owner, repo_name, bucket ASC
                    """);
            ps.setArray(1, con.createArrayOf("text", fullNames.toArray(String[]::new)));
            ps.setTimestamp(2, Timestamp.valueOf(from));
            ps.setTimestamp(3, Timestamp.valueOf(to));
            return ps;
        }, MAPPER);
    }

    /** repo_time metrics upsert 배치 (GhArchiveService용) */
    @Transactional
    public void batchUpsert(Map<String, int[]> metrics, Timestamp bucket) {
        if (metrics.isEmpty()) return;
        List<Map.Entry<String, int[]>> entries = List.copyOf(metrics.entrySet());
        jdbc.batchUpdate("""
                        INSERT INTO repo_time
                            (repo_owner, repo_name, bucket,
                             watch, commit_count, pr_created, pr_merged,
                             issue_opened, issue_closed, star_count, release_count,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        ON CONFLICT (repo_owner, repo_name, bucket) DO UPDATE SET
                            watch         = EXCLUDED.watch,
                            commit_count  = EXCLUDED.commit_count,
                            pr_created    = EXCLUDED.pr_created,
                            pr_merged     = EXCLUDED.pr_merged,
                            issue_opened  = EXCLUDED.issue_opened,
                            issue_closed  = EXCLUDED.issue_closed,
                            star_count    = EXCLUDED.star_count,
                            release_count = EXCLUDED.release_count,
                            updated_at    = NOW()
                        """,
                entries,
                entries.size(),
                (ps, entry) -> {
                    String[] parts = entry.getKey().split("/", 2);
                    int[] m = entry.getValue();
                    ps.setString(1, parts[0]);
                    ps.setString(2, parts[1]);
                    ps.setTimestamp(3, bucket);
                    ps.setInt(4, m[0]);
                    ps.setInt(5, m[1]);
                    ps.setInt(6, m[2]);
                    ps.setInt(7, m[3]);
                    ps.setInt(8, m[4]);
                    ps.setInt(9, m[5]);
                    ps.setInt(10, m[6]);
                    ps.setInt(11, m[7]);
                });
    }

    /** 스코어 UPDATE 배치 */
    @Transactional
    public void batchUpdateScores(List<ScoreUpdate> updates) {
        if (updates.isEmpty()) return;
        jdbc.batchUpdate("""
                        UPDATE repo_time SET
                            active_score     = ?,
                            health_score     = ?,
                            brightness_score = ?,
                            size_score       = ?,
                            updated_at       = NOW()
                        WHERE repo_owner = ? AND repo_name = ? AND bucket = ?
                        """,
                updates,
                updates.size(),
                (ps, u) -> {
                    ps.setDouble(1, u.activeScore());
                    ps.setDouble(2, u.healthScore());
                    ps.setDouble(3, u.brightnessScore());
                    ps.setDouble(4, u.sizeScore());
                    ps.setString(5, u.owner());
                    ps.setString(6, u.name());
                    ps.setTimestamp(7, Timestamp.valueOf(u.bucket()));
                });
    }
}
