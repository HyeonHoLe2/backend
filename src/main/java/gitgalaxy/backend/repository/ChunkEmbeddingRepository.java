package gitgalaxy.backend.repository;

import gitgalaxy.backend.model.ChunkDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * chunk_embeddings 테이블 접근 (pgvector 네이티브 연산 사용).
 * JPA 미사용 → JdbcTemplate으로 직접 관리.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ChunkEmbeddingRepository {

    private final JdbcTemplate jdbc;

    public boolean existsByChunkId(String chunkId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_embeddings WHERE chunk_id = ?",
                Integer.class, chunkId);
        return count != null && count > 0;
    }

    private static final int EXISTS_QUERY_CHUNK = 5_000;

    /**
     * 이미 저장된 chunk_id 일괄 조회 (chunk_id 유니크 인덱스 → Index Only Scan).
     * 청크마다 existsByChunkId를 부르면 DB 왕복이 청크 수만큼 생겨, 목록을 ANY(배열)로 한 번에 확인.
     */
    public java.util.Set<String> findExistingChunkIds(List<String> chunkIds) {
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (int i = 0; i < chunkIds.size(); i += EXISTS_QUERY_CHUNK) {
            List<String> part = chunkIds.subList(i, Math.min(i + EXISTS_QUERY_CHUNK, chunkIds.size()));
            jdbc.query(con -> {
                var ps = con.prepareStatement("SELECT chunk_id FROM chunk_embeddings WHERE chunk_id = ANY(?)");
                ps.setArray(1, con.createArrayOf("text", part.toArray(String[]::new)));
                return ps;
            }, rs -> { existing.add(rs.getString(1)); });
        }
        return existing;
    }

    private static final String UPSERT_SQL = """
            INSERT INTO chunk_embeddings
                (chunk_id, repo_owner, repo_name, file_path, heading, content, url, embedding, embedded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
            ON CONFLICT (chunk_id) DO UPDATE SET
                content     = EXCLUDED.content,
                embedding   = EXCLUDED.embedding,
                embedded_at = EXCLUDED.embedded_at
            """;

    /** chunk + 벡터 배치 upsert: 임베딩 API 묶음(20개)과 같은 단위로 한 번의 DB 왕복에 저장 */
    public void batchUpsert(List<ChunkDocument> chunks, List<String> vectorStrs) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.batchUpdate(UPSERT_SQL, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ChunkDocument c = chunks.get(i);
                ps.setString(1, c.getChunkId());
                ps.setString(2, c.getRepoOwner());
                ps.setString(3, c.getRepoName());
                ps.setString(4, c.getFilePath());
                ps.setString(5, c.getHeading());
                ps.setString(6, c.getContent());
                ps.setString(7, c.getUrl());
                ps.setString(8, vectorStrs.get(i));
                ps.setTimestamp(9, now);
            }

            @Override
            public int getBatchSize() { return chunks.size(); }
        });
    }

    /** chunk + 벡터 upsert (ON CONFLICT chunk_id) */
    public void upsert(ChunkDocument chunk, String vectorStr) {
        jdbc.update(UPSERT_SQL,
                chunk.getChunkId(),
                chunk.getRepoOwner(),
                chunk.getRepoName(),
                chunk.getFilePath(),
                chunk.getHeading(),
                chunk.getContent(),
                chunk.getUrl(),
                vectorStr,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** cosine 유사도 기반 repo 내 검색 */
    public List<Map<String, Object>> findSimilar(String queryVector, String owner, String repo, int limit) {
        return jdbc.queryForList("""
                SELECT chunk_id, repo_owner, repo_name, file_path, heading, content, url,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM chunk_embeddings
                WHERE repo_owner = ? AND repo_name = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                queryVector, owner, repo, queryVector, limit);
    }

    /** cosine 유사도 기반 전체 repo 검색 */
    public List<Map<String, Object>> findSimilarGlobal(String queryVector, int limit) {
        return jdbc.queryForList("""
                SELECT chunk_id, repo_owner, repo_name, file_path, heading, content, url,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM chunk_embeddings
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                queryVector, queryVector, limit);
    }

    /** Hybrid 검색용: Dense Top-N + embedded_at 반환 (재랭킹은 서비스 레이어에서 수행) */
    public List<Map<String, Object>> findSimilarGlobalWithTime(String queryVector, int limit) {
        return jdbc.queryForList("""
                SELECT chunk_id, repo_owner, repo_name, file_path, heading, content, url,
                       1 - (embedding <=> ?::vector) AS similarity,
                       embedded_at
                FROM chunk_embeddings
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                queryVector, queryVector, limit);
    }
}
