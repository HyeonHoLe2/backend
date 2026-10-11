package gitgalaxy.backend;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.ChunkEmbeddingRepository;
import gitgalaxy.backend.service.EmbeddingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 청크 적재 DB 비용 비교: 건별(존재 확인 + INSERT) vs 일괄(ANY 조회 + 20건 배치 INSERT).
 * 임베딩 API는 제외하고 DB 작업만 측정. 실제 chunk_embeddings(HNSW 포함)에 bench- 데이터를 넣고 끝나면 삭제.
 * 실행: RUN_BENCH=true ./gradlew test --tests ChunkStoreBenchmarkTest
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "true")
class ChunkStoreBenchmarkTest {

    private static final int TOTAL = 8_000;        // 매시 수집 1회 청크 규모
    private static final int PRE_EXISTING = 4_000; // 이미 저장된 청크 비율 50%
    private static final int BATCH = 20;           // 임베딩 API 묶음 크기와 동일

    @Autowired private ChunkEmbeddingRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private final String prefix = "bench-store-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private final Random random = new Random(42);

    @Test
    void compare() {
        List<ChunkDocument> chunks = new ArrayList<>();
        List<String> vectors = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            chunks.add(ChunkDocument.builder()
                    .chunkId(prefix + i).repoOwner("bench").repoName("bench").filePath("README.md")
                    .heading("h" + i).content("bench content " + i).url("https://example.com/" + i).build());
            vectors.add(randomVector());
        }
        // 절반은 미리 저장 (측정 대상 아님)
        for (int i = 0; i < PRE_EXISTING; i += BATCH) {
            repository.batchUpsert(chunks.subList(i, i + BATCH), vectors.subList(i, i + BATCH));
        }

        for (int round = 1; round <= 2; round++) {
            long[] before = runPerRow(chunks, vectors);
            deleteNewlyInserted();
            long[] after = runBatched(chunks, vectors);
            deleteNewlyInserted();
            System.out.printf("%n[ROUND %d] 청크 %,d개 (기존 %,d / 신규 %,d)%n", round, TOTAL, PRE_EXISTING, TOTAL - PRE_EXISTING);
            System.out.printf("  건별   : 존재 확인 %,6d ms (DB 왕복 %,d회) + 저장 %,6d ms (DB 왕복 %,d회) = %,6d ms%n",
                    before[0], TOTAL, before[1], TOTAL - PRE_EXISTING, before[0] + before[1]);
            int batchCalls = (TOTAL - PRE_EXISTING + BATCH - 1) / BATCH;
            System.out.printf("  일괄   : 존재 확인 %,6d ms (DB 왕복 %,d회) + 저장 %,6d ms (DB 왕복 %,d회) = %,6d ms%n",
                    after[0], (TOTAL + 4_999) / 5_000, after[1], batchCalls, after[0] + after[1]);
        }
    }

    /** 기존 방식: 청크마다 existsByChunkId → 없으면 upsert 1건 */
    private long[] runPerRow(List<ChunkDocument> chunks, List<String> vectors) {
        long t0 = System.nanoTime();
        List<Integer> toInsert = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!repository.existsByChunkId(chunks.get(i).getChunkId())) toInsert.add(i);
        }
        long t1 = System.nanoTime();
        for (int i : toInsert) repository.upsert(chunks.get(i), vectors.get(i));
        long t2 = System.nanoTime();
        return new long[]{(t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000};
    }

    /** 개선 방식: ANY(배열)로 일괄 존재 확인 → 20건씩 batchUpsert */
    private long[] runBatched(List<ChunkDocument> chunks, List<String> vectors) {
        long t0 = System.nanoTime();
        Set<String> existing = repository.findExistingChunkIds(chunks.stream().map(ChunkDocument::getChunkId).toList());
        List<ChunkDocument> c = new ArrayList<>();
        List<String> v = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!existing.contains(chunks.get(i).getChunkId())) { c.add(chunks.get(i)); v.add(vectors.get(i)); }
        }
        long t1 = System.nanoTime();
        for (int i = 0; i < c.size(); i += BATCH) {
            int end = Math.min(i + BATCH, c.size());
            repository.batchUpsert(c.subList(i, end), v.subList(i, end));
        }
        long t2 = System.nanoTime();
        return new long[]{(t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000};
    }

    /** 측정 중 새로 넣은 절반만 삭제 → 다음 측정도 같은 조건(기존 4,000 / 신규 4,000) */
    private void deleteNewlyInserted() {
        jdbc.update("DELETE FROM chunk_embeddings WHERE chunk_id LIKE ? AND split_part(chunk_id, '-', 4)::int >= ?",
                prefix + "%", PRE_EXISTING);
    }

    @AfterAll
    void cleanup() {
        int n = jdbc.update("DELETE FROM chunk_embeddings WHERE chunk_id LIKE ?", prefix + "%");
        System.out.printf("%n측정 데이터 %,d행 삭제%n", n);
    }

    private String randomVector() {
        float[] v = new float[768];
        double norm = 0;
        for (int i = 0; i < v.length; i++) { v[i] = (float) random.nextGaussian(); norm += v[i] * v[i]; }
        for (int i = 0; i < v.length; i++) v[i] /= (float) Math.sqrt(norm);
        return EmbeddingService.toVectorString(v);
    }
}
