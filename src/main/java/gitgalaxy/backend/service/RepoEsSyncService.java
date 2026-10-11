package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.metrics.CollectorMetrics;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DB → ES 동기화 (Polling Publisher).
 * repo 저장 트랜잭션에서 es_synced_at = NULL로 표시해 두면, 주기적으로 대기분을 모아 ES bulk 색인 후 반영 시각을 기록.
 * - ES 장애 중에는 표시가 남아 있다가 복구 후 자동 반영 (최종 일관성)
 * - 같은 repo를 다시 색인해도 문서 id가 같아 덮어쓰기 → 중복 반영 무해
 * - 읽은 뒤 다시 수정된 repo는 반영 표시를 하지 않음 (last_collected_at 비교) → 다음 주기에 최신 값으로 재색인
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoEsSyncService {

    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 50; // 1회 최대 25,000건, 나머지는 다음 주기

    private final RepoRepository repoRepository;
    private final RepoIndexService repoIndexService;
    private final JdbcTemplate jdbc;
    private final CollectorMetrics collectorMetrics;

    @Scheduled(fixedDelayString = "${es.sync.interval-ms:30000}", initialDelayString = "${es.sync.initial-delay-ms:20000}")
    @SchedulerLock(name = "repoEsSync", lockAtMostFor = "5m", lockAtLeastFor = "5s")
    public void syncPending() {
        long start = System.nanoTime();
        int synced = 0;
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM repos WHERE es_synced_at IS NULL ORDER BY id LIMIT ?", Long.class, BATCH_SIZE);
            if (ids.isEmpty()) break;
            List<Repo> repos = repoRepository.findAllById(ids);
            try {
                repoIndexService.indexBulk(repos);
            } catch (Exception e) {
                collectorMetrics.esSync("failed", repos.size());
                log.warn("ES 동기화 실패 ({}건, 다음 주기에 재시도): {}", repos.size(), e.getMessage());
                break;
            }
            // 색인한 값 그대로일 때만 반영 표시 (그 사이 저장된 repo는 NULL로 남아 다음 주기에 재색인)
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbc.batchUpdate(
                    "UPDATE repos SET es_synced_at = ? WHERE id = ? AND last_collected_at IS NOT DISTINCT FROM ?",
                    repos, repos.size(), (ps, r) -> {
                        ps.setTimestamp(1, now);
                        ps.setLong(2, r.getId());
                        ps.setTimestamp(3, r.getLastCollectedAt() == null ? null : Timestamp.valueOf(r.getLastCollectedAt()));
                    });
            synced += repos.size();
            collectorMetrics.esSync("indexed", repos.size());
            if (ids.size() < BATCH_SIZE) break;
        }
        if (synced > 0) {
            log.info("ES 동기화: {}건 색인 ({}ms)", synced, (System.nanoTime() - start) / 1_000_000);
        }
    }
}
