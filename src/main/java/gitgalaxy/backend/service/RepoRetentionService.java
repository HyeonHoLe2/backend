package gitgalaxy.backend.service;

import gitgalaxy.backend.config.RetentionProperties;
import gitgalaxy.backend.repository.RepoElasticsearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * repo 보관 정책: 최근 N일 동안 일간 트렌딩 rankCutoff 안에 한 번도 들지 못한 repo와
 * 그 임베딩·시간별 지표·요약·트렌드 이유·ES 문서를 삭제. 즐겨찾기된 repo는 보호.
 * 트렌딩 기록이 N일치 쌓이기 전에는 삭제하지 않음 (기록이 짧으면 대부분이 삭제 대상이 되므로).
 * DB에 외래키가 없어 관련 테이블을 직접 정리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoRetentionService {

    private final RetentionProperties props;
    private final JdbcTemplate jdbc;
    private final RepoElasticsearchRepository repoElasticsearchRepository;

    public record Result(boolean applied, String reason, int keepRepos, int repos, int chunks,
                         int hourlyMetrics, int summaries) {}

    /** 남길 repo: 기간 내 트렌딩 순위권 + 즐겨찾기 */
    private static final String KEEP = """
            SELECT repo_full_name AS full_name FROM trending_daily WHERE trend_date >= ? AND rank <= ?
            UNION
            SELECT r.full_name FROM repos r JOIN user_favorite f ON f.repo_id = r.id
            """;

    @Transactional
    public Result run(boolean dryRun) {
        if (!props.isEnabled()) return new Result(false, "비활성화", 0, 0, 0, 0, 0);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Date from = Date.valueOf(today.minusDays(props.getWindowDays()));
        Integer days = jdbc.queryForObject(
                "SELECT count(DISTINCT trend_date) FROM trending_daily WHERE trend_date >= ? AND trend_date < ?",
                Integer.class, from, Date.valueOf(today));
        if (days == null || days < props.getWindowDays()) {
            String reason = "트렌딩 기록 " + (days == null ? 0 : days) + "/" + props.getWindowDays() + "일 → 기간이 쌓일 때까지 삭제 보류";
            log.info("repo 보관 정책: {}", reason);
            return new Result(false, reason, 0, 0, 0, 0, 0);
        }

        Object[] keepArgs = {from, props.getRankCutoff()};
        int keep = count("SELECT count(*) FROM (" + KEEP + ") k", keepArgs);
        String notKept = "NOT IN (SELECT full_name FROM (" + KEEP + ") k)";

        if (dryRun) {
            Result r = new Result(false, "dry-run", keep,
                    count("SELECT count(*) FROM repos WHERE full_name " + notKept, keepArgs),
                    count("SELECT count(*) FROM chunk_embeddings WHERE repo_owner || '/' || repo_name " + notKept, keepArgs),
                    count("SELECT count(*) FROM repo_time WHERE repo_owner || '/' || repo_name " + notKept, keepArgs),
                    count("SELECT count(*) FROM repo_ai_summaries WHERE owner || '/' || repo " + notKept, keepArgs));
            log.info("repo 보관 정책 (dry-run): {}", r);
            return r;
        }

        List<String> esIds = jdbc.queryForList("SELECT id::text FROM repos WHERE full_name " + notKept, String.class, keepArgs);
        int chunks = jdbc.update("DELETE FROM chunk_embeddings WHERE repo_owner || '/' || repo_name " + notKept, keepArgs);
        int metrics = jdbc.update("DELETE FROM repo_time WHERE repo_owner || '/' || repo_name " + notKept, keepArgs);
        // summary는 대용량 객체(oid) → 행만 지우면 본문이 고아로 남으므로 먼저 해제
        jdbc.query("SELECT lo_unlink(summary) FROM repo_ai_summaries WHERE summary IS NOT NULL AND owner || '/' || repo " + notKept,
                rs -> {}, keepArgs);
        int summaries = jdbc.update("DELETE FROM repo_ai_summaries WHERE owner || '/' || repo " + notKept, keepArgs);
        jdbc.update("DELETE FROM repo_similar WHERE repo_full_name " + notKept + " OR neighbor_full_name " + notKept,
                from, props.getRankCutoff(), from, props.getRankCutoff());
        int repos = jdbc.update("DELETE FROM repos WHERE full_name " + notKept, keepArgs);

        try {
            for (int i = 0; i < esIds.size(); i += 1_000) {
                repoElasticsearchRepository.deleteAllById(esIds.subList(i, Math.min(i + 1_000, esIds.size())));
            }
        } catch (Exception e) {
            log.warn("ES 문서 삭제 실패 (다음 재색인 때 정리): {}", e.getMessage());
        }

        Result r = new Result(true, "적용", keep, repos, chunks, metrics, summaries);
        log.info("repo 보관 정책 적용: {}", r);
        return r;
    }

    private int count(String sql, Object[] args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
