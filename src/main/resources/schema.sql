-- pgvector 확장 활성화 (최초 1회만 실행됨)
CREATE EXTENSION IF NOT EXISTS vector;

-- RAG용 청크 임베딩 테이블 (JPA 미사용 → 직접 관리)
CREATE TABLE IF NOT EXISTS chunk_embeddings (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    VARCHAR(512) UNIQUE NOT NULL,
    repo_owner  VARCHAR(255),
    repo_name   VARCHAR(255),
    file_path   TEXT,
    heading     TEXT,
    content     TEXT,
    url         TEXT,
    embedding   vector(1536),
    embedded_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_repo
    ON chunk_embeddings (repo_owner, repo_name);
