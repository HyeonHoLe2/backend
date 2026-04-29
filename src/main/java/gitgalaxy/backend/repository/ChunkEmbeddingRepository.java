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

    /** chunk + 벡터 upsert (ON CONFLICT chunk_id) */
    public void upsert(ChunkDocument chunk, String vectorStr) {
        jdbc.update("""
                INSERT INTO chunk_embeddings
                    (chunk_id, repo_owner, repo_name, file_path, heading, content, url, embedding, embedded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    content     = EXCLUDED.content,
                    embedding   = EXCLUDED.embedding,
                    embedded_at = EXCLUDED.embedded_at
                """,
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
}
