package gitgalaxy.backend.service;

import gitgalaxy.backend.model.RepoProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 레포 단위 임베딩 (레포당 벡터 1개): 설명 + 토픽 + README 본문 정리(앞 1,000자).
 * README 청크마다 임베딩하던 방식(레포당 약 12개) 대비 임베딩 호출·저장 공간이 1/10 수준이고,
 * 유사 레포 정확도는 README 첫 청크 대비 Hit@5 57% → 77% (흔한 토픽 제외, 실측).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoEmbeddingService {

    private static final int README_CHARS = 1_000;
    private static final int EMBED_BATCH = 20;

    private final EmbeddingService embeddingService;
    private final gitgalaxy.backend.config.VertexAiProperties vertexProps;
    private final StarStatsService starStatsService;
    private final JdbcTemplate jdbc;

    static String buildText(RepoProfile p) {
        StringBuilder sb = new StringBuilder();
        if (p.meta().description() != null && !p.meta().description().isBlank()) sb.append(p.meta().description().strip()).append('\n');
        if (!p.topics().isEmpty()) sb.append("Topics: ").append(String.join(", ", p.topics())).append('\n');
        sb.append(ReadmeCleaner.clean(p.readme(), README_CHARS));
        return sb.toString().strip();
    }

    /** 이미 받은 프로필로 임베딩·저장 (가벼운 등록에서 GraphQL 재조회 없이 사용). 저장한 repo 이름 반환 */
    public List<String> embedProfiles(Map<String, RepoProfile> profiles) {
        List<String> names = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        profiles.forEach((name, p) -> {
            String text = buildText(p);
            if (!text.isBlank()) { names.add(name); texts.add(text); }
        });
        // 묶음(20개)마다 임베딩 호출 + 저장을 동시에 진행 (Vertex 응답 대기 시간이 대부분)
        List<String> saved = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, vertexProps.getEmbeddingConcurrency()));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < names.size(); i += EMBED_BATCH) {
                List<String> n = names.subList(i, Math.min(i + EMBED_BATCH, names.size()));
                List<String> t = texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()));
                futures.add(pool.submit(() -> {
                    try {
                        List<float[]> vectors = embeddingService.embedBatch(t);
                        upsert(n, t, vectors);
                        saved.addAll(n);
                    } catch (Exception e) {
                        log.warn("레포 임베딩 실패 ({}개): {}", n.size(), e.getMessage());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("레포 임베딩 작업 오류: {}", e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(saved);
    }

    /** GraphQL로 프로필을 받아 임베딩 (트렌딩 풀 등록·백필용) */
    public List<String> refresh(List<String> fullNames) {
        if (fullNames.isEmpty()) return List.of();
        return embedProfiles(starStatsService.fetchProfiles(fullNames));
    }

    /** 레포 임베딩이 없는 추적 repo 일괄 생성. 500개씩 진행 로그 */
    public int backfillMissing() {
        List<String> missing = jdbc.queryForList("""
                SELECT r.full_name FROM repos r
                WHERE r.tracked AND NOT EXISTS (SELECT 1 FROM repo_embeddings e WHERE e.repo_full_name = r.full_name)
                ORDER BY r.id
                """, String.class);
        long t0 = System.currentTimeMillis();
        int done = 0;
        for (int i = 0; i < missing.size(); i += 500) {
            done += refresh(missing.subList(i, Math.min(i + 500, missing.size()))).size();
            log.info("레포 임베딩 백필 {}/{} ({}s)", Math.min(i + 500, missing.size()), missing.size(),
                    (System.currentTimeMillis() - t0) / 1000);
        }
        return done;
    }

    private void upsert(List<String> names, List<String> texts, List<float[]> vectors) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        jdbc.batchUpdate("""
                INSERT INTO repo_embeddings (repo_full_name, embedding, text_length, updated_at) VALUES (?, ?::vector, ?, ?)
                ON CONFLICT (repo_full_name) DO UPDATE
                    SET embedding = EXCLUDED.embedding, text_length = EXCLUDED.text_length, updated_at = EXCLUDED.updated_at
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setString(1, names.get(i));
                ps.setString(2, EmbeddingService.toVectorString(vectors.get(i)));
                ps.setInt(3, texts.get(i).length());
                ps.setTimestamp(4, now);
            }

            @Override
            public int getBatchSize() { return names.size(); }
        });
    }
}
