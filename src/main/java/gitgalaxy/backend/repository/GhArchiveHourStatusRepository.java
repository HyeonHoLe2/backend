package gitgalaxy.backend.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * gharchive_hour_status: 시간대별 처리 상태 = DB 기반 재시도 큐 (failed → 재시도, dead → 포기·알림).
 * 시각 컬럼은 모두 UTC (hour_bucket이 UTC라 맞춤. 앱 DB 세션 타임존은 KST라 now()를 UTC로 변환해 저장)
 */
@Repository
@RequiredArgsConstructor
public class GhArchiveHourStatusRepository {

    /** 처리 중으로 남아 있어도 이 시간이 지나면 앱이 죽은 것으로 보고 다시 가져감 */
    private static final String STALE_PROCESSING = "2 hours";

    private final JdbcTemplate jdbc;

    /**
     * 시간대 처리 권한 획득 (attempts + 1). 다른 실행이 처리 중이면 false
     * → 스케줄 배치·재처리·수동 실행이 같은 시간대를 동시에 처리하지 않음.
     */
    public boolean claim(LocalDateTime hour) {
        return jdbc.update("""
                INSERT INTO gharchive_hour_status (hour_bucket, status, attempts, started_at)
                VALUES (?, 'processing', 1, (now() AT TIME ZONE 'UTC'))
                ON CONFLICT (hour_bucket) DO UPDATE
                   SET status = 'processing', attempts = gharchive_hour_status.attempts + 1, started_at = (now() AT TIME ZONE 'UTC')
                 WHERE gharchive_hour_status.status <> 'processing'
                    OR gharchive_hour_status.started_at < (now() AT TIME ZONE 'UTC') - interval '""" + STALE_PROCESSING + "'",
                Timestamp.valueOf(hour)) == 1;
    }

    public void markSuccess(LocalDateTime hour, long durationMs) {
        jdbc.update("""
                UPDATE gharchive_hour_status
                   SET status = 'success', last_error = NULL, next_retry_at = NULL, finished_at = (now() AT TIME ZONE 'UTC'), duration_ms = ?
                 WHERE hour_bucket = ?""", durationMs, Timestamp.valueOf(hour));
    }

    /**
     * 실패 기록. 한도 미만이면 failed + 다음 재시도 시각(1h → 2h → 4h → 8h → 최대 12h), 한도 도달이면 dead.
     * 반환: 기록된 상태
     */
    public String markFailed(LocalDateTime hour, String error, long durationMs, int maxAttempts) {
        return jdbc.queryForObject("""
                UPDATE gharchive_hour_status
                   SET status = CASE WHEN attempts >= ? THEN 'dead' ELSE 'failed' END,
                       next_retry_at = CASE WHEN attempts >= ? THEN NULL
                                            ELSE (now() AT TIME ZONE 'UTC') + make_interval(hours => LEAST(12, power(2, attempts - 1)::int)) END,
                       last_error = ?, finished_at = (now() AT TIME ZONE 'UTC'), duration_ms = ?
                 WHERE hour_bucket = ?
                RETURNING status""", String.class, maxAttempts, maxAttempts, error, durationMs, Timestamp.valueOf(hour));
    }

    /**
     * 기록조차 없는 시간대(스케줄 미실행: 절전·배치 지연 등)를 재시도 대상으로 등록. 등록 수 반환.
     * 테이블에 기록된 가장 이른 시간대보다 앞은 보지 않음 (도입 이전 구간을 통째로 받지 않도록).
     */
    public int registerMissing(LocalDateTime from, LocalDateTime toExclusive) {
        return jdbc.update("""
                INSERT INTO gharchive_hour_status (hour_bucket, status, attempts, last_error, next_retry_at)
                SELECT h, 'failed', 0, '처리 기록 없음 (스케줄 미실행)', (now() AT TIME ZONE 'UTC')
                  FROM generate_series(GREATEST(?::timestamp, (SELECT min(hour_bucket) FROM gharchive_hour_status)),
                                       ?::timestamp - interval '1 hour', interval '1 hour') AS h
                ON CONFLICT (hour_bucket) DO NOTHING""", Timestamp.valueOf(from), Timestamp.valueOf(toExclusive));
    }

    /** 재시도 시각이 된 실패 시간대 (오래된 순). 매시 배치 시각과 몇 분 어긋나도 놓치지 않도록 15분 여유 */
    public List<LocalDateTime> findDue(int limit) {
        return jdbc.query("""
                SELECT hour_bucket FROM gharchive_hour_status
                 WHERE status = 'failed' AND next_retry_at <= (now() AT TIME ZONE 'UTC') + interval '15 minutes'
                 ORDER BY hour_bucket LIMIT ?""",
                (rs, i) -> rs.getTimestamp(1).toLocalDateTime(), limit);
    }

    public long countByStatus(String status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM gharchive_hour_status WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    /** 성공이 아닌 시간대 목록 (관리 API 확인용) */
    public List<Map<String, Object>> findUnsuccessful(int limit) {
        return jdbc.queryForList("""
                SELECT to_char(hour_bucket, 'YYYY-MM-DD HH24:00') AS hour_utc, status, attempts, last_error,
                       to_char(next_retry_at, 'YYYY-MM-DD HH24:MI') AS next_retry_utc,
                       to_char(finished_at, 'YYYY-MM-DD HH24:MI') AS finished_utc
                  FROM gharchive_hour_status WHERE status <> 'success'
                 ORDER BY hour_bucket DESC LIMIT ?""", limit);
    }
}
