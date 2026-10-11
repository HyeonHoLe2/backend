package gitgalaxy.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 유사도가 임계값 이상인 레포 쌍을 양방향으로 repo_similar에 저장 (API는 읽기만).
 * 순위(Top-K)가 아닌 절대 기준이라 새 레포가 들어와도 기존 쌍은 그대로 유효 → 새 레포 주변 쌍만 추가하면 되고 전체 재계산 불필요.
 * 쿼리 벡터를 먼저 조회해 파라미터로 넘김 → HNSW 사용 (같은 쿼리 안의 조인으로 넘기면 전체 비교).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoSimilarityService {

    private static final int CANDIDATES = 30; // HNSW 후보 수. 화면은 레포당 최대 3개만 그리므로 충분

    private final JdbcTemplate jdbc;

    /** 저장 기준. 화면 기준(galaxy.similar-min)보다 낮게 잡아 두면 화면 기준을 조금 낮춰도 재계산 불필요 */
    @Value("${galaxy.similar-store-min:0.75}")
    private double storeMin;

    /** 임베딩이 새로 생기거나 바뀐 레포의 쌍을 다시 계산 (해당 레포가 포함된 쌍만 교체) */
    public int computeFor(Collection<String> repos) {
        long t0 = System.currentTimeMillis();
        int done = 0, pairs = 0;
        for (String repo : repos) {
            List<String> vec = jdbc.queryForList(
                    "SELECT embedding::text FROM repo_embeddings WHERE repo_full_name = ?", String.class, repo);
            if (vec.isEmpty()) continue;
            String owner = repo.substring(0, repo.indexOf('/'));
            List<Object[]> rows = jdbc.query("""
                    SELECT repo_full_name, 1 - (embedding <=> ?::vector) FROM repo_embeddings
                    ORDER BY embedding <=> ?::vector LIMIT ?
                    """, (rs, i) -> new Object[]{rs.getString(1), rs.getDouble(2)}, vec.get(0), vec.get(0), CANDIDATES);
            List<Object[]> matched = new ArrayList<>();
            for (Object[] r : rows) {
                String n = (String) r[0];
                if (n.equals(repo) || n.startsWith(owner + "/")) continue; // 자기 자신·같은 소유자 제외 (소유자 연결은 따로 표시)
                if ((double) r[1] < storeMin) break; // 유사도 내림차순이므로 이후는 모두 미달
                matched.add(r);
            }
            save(repo, matched);
            pairs += matched.size();
            done++;
        }
        log.info("유사 레포 계산 {}개, 쌍 {}개 ({}ms)", done, pairs, System.currentTimeMillis() - t0);
        return done;
    }

    /** 전체 재계산: 저장 기준(storeMin)을 바꿨을 때나 백필용. 정기 실행 불필요 */
    public int computeAll() {
        jdbc.update("TRUNCATE repo_similar");
        return computeFor(jdbc.queryForList("SELECT repo_full_name FROM repo_embeddings", String.class));
    }

    /** 레포 벡터가 바뀌었으므로 이 레포가 포함된 기존 쌍은 모두 지우고 양방향으로 다시 저장 */
    private void save(String repo, List<Object[]> matched) {
        jdbc.update("DELETE FROM repo_similar WHERE repo_full_name = ? OR neighbor_full_name = ?", repo, repo);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        List<Object[]> both = new ArrayList<>(matched.size() * 2);
        for (Object[] r : matched) {
            both.add(new Object[]{repo, r[0], r[1]});
            both.add(new Object[]{r[0], repo, r[1]});
        }
        jdbc.batchUpdate("""
                INSERT INTO repo_similar (repo_full_name, neighbor_full_name, similarity, computed_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (repo_full_name, neighbor_full_name) DO UPDATE SET similarity = EXCLUDED.similarity, computed_at = EXCLUDED.computed_at
                """, both, both.size(), (ps, r) -> {
                    ps.setString(1, (String) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setDouble(3, (double) r[2]);
                    ps.setTimestamp(4, now);
                });
    }
}
