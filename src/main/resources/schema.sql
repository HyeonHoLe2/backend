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
    embedding   vector(768),
    embedded_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_repo
    ON chunk_embeddings (repo_owner, repo_name);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_hnsw
    ON chunk_embeddings USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ─── 일간 트렌딩 (UTC 하루 스타 증가량) ─────────────────────────
-- GH Archive 시간별 스타 이벤트 수 (추적 여부와 무관한 모든 repo, 후보 선정용)
CREATE TABLE IF NOT EXISTS repo_star_hourly (
    repo_full_name VARCHAR(255) NOT NULL,
    bucket         TIMESTAMP    NOT NULL,   -- UTC 시각 (GH Archive 파일 시간)
    stars          INT          NOT NULL,
    PRIMARY KEY (repo_full_name, bucket)
);
CREATE INDEX IF NOT EXISTS idx_repo_star_hourly_bucket ON repo_star_hourly (bucket);

-- 매시 정각(UTC) 기준 후보 repo의 GitHub 누적 스타 수 (S0/S1, 처음 발견 시점)
-- 실제 조회는 GH Archive 수집 직후(매시 10분 전후)라 정각 대비 약 10분 지연
CREATE TABLE IF NOT EXISTS repo_star_snapshot (
    repo_full_name VARCHAR(255) NOT NULL,
    snapshot_hour  TIMESTAMP    NOT NULL,   -- UTC 정각
    stargazers     INT          NOT NULL,
    captured_at    TIMESTAMP    NOT NULL,   -- 실제 조회 시각 (UTC)
    PRIMARY KEY (repo_full_name, snapshot_hour)
);
CREATE INDEX IF NOT EXISTS idx_repo_star_snapshot_hour ON repo_star_snapshot (snapshot_hour);

-- 하루(UTC) 스타 증가량 순위
CREATE TABLE IF NOT EXISTS trending_daily (
    trend_date     DATE         NOT NULL,
    rank           INT          NOT NULL,
    repo_full_name VARCHAR(255) NOT NULL,
    stars_gained   INT          NOT NULL,
    stargazers     INT          NOT NULL,   -- S1 (다음날 00:00 UTC 스냅샷)
    method         VARCHAR(20)  NOT NULL,   -- full: 당일 00시 S0 있음 / partial: 당일 처음 발견 시점부터
    computed_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (trend_date, rank)
);

-- 일간 트렌딩 repo의 같은 날(UTC 00~24시) GH Archive 활동량 → 별 밝기
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS pushes          INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS prs_opened      INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS prs_merged      INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS issues_opened   INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS issues_closed   INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS releases        INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS forks           INT;
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS activity_score  DOUBLE PRECISION;  -- 가중합 (상한 없음)
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS brightness      DOUBLE PRECISION;  -- 0~1, 같은 날 순위 안에서 상대값 (로그 스케일)
ALTER TABLE trending_daily ADD COLUMN IF NOT EXISTS activity_hours  INT;               -- 집계에 쓴 GH Archive 시간대 수 (24 미만이면 일부 누락)

-- 일간 트렌딩 repo의 "같은 시기에 있었던 일" 근거 + 근거 기반 요약
CREATE TABLE IF NOT EXISTS trend_evidence (
    trend_date     DATE         NOT NULL,
    repo_full_name VARCHAR(255) NOT NULL,
    evidence       TEXT         NOT NULL,   -- JSON (릴리즈, Hacker News, 생성일, 소유자)
    summary        TEXT,                    -- 근거만으로 만든 요약 (근거 없으면 고정 문구)
    generated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (trend_date, repo_full_name)
);
ALTER TABLE trend_evidence ADD COLUMN IF NOT EXISTS trigger_text TEXT;         -- 계기 (규칙 기반 추정)
ALTER TABLE trend_evidence ADD COLUMN IF NOT EXISTS strength     VARCHAR(10);  -- strong / medium / weak / none
ALTER TABLE trend_evidence ADD COLUMN IF NOT EXISTS appeal       TEXT;         -- 끌린 점 (근거 내용 요약)

-- ─── 레포 단위 임베딩 / 유사 레포 ───────────────────────────
-- 레포당 벡터 1개: 설명 + 토픽 + README 본문 정리(HTML·배지·코드 제거, 앞 1,000자)
-- (README 첫 청크 대비 유사 레포 Hit@5 57% → 77%, 흔한 토픽 제외·기준 레포 200개 실측)
CREATE TABLE IF NOT EXISTS repo_embeddings (
    repo_full_name VARCHAR(255) PRIMARY KEY,
    embedding      vector(768) NOT NULL,
    text_length    INT         NOT NULL,
    updated_at     TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_repo_embeddings_hnsw
    ON repo_embeddings USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- 유사도가 임계값 이상인 레포 쌍 (양방향 저장, 배치에서 미리 계산, API는 읽기만)
CREATE TABLE IF NOT EXISTS repo_similar (
    repo_full_name     VARCHAR(255)     NOT NULL,
    neighbor_full_name VARCHAR(255)     NOT NULL,
    similarity         DOUBLE PRECISION NOT NULL,
    computed_at        TIMESTAMP        NOT NULL,
    PRIMARY KEY (repo_full_name, neighbor_full_name)
);
-- 레포 재계산 시 neighbor 쪽 쌍 삭제용
CREATE INDEX IF NOT EXISTS idx_repo_similar_neighbor ON repo_similar (neighbor_full_name);

-- ES 반영 대기 repo 조회용 (Polling Publisher). 대기분만 담는 부분 인덱스라 작게 유지됨
CREATE INDEX IF NOT EXISTS idx_repos_es_pending ON repos (id) WHERE es_synced_at IS NULL;

-- GH Archive 시간대 처리 상태 (실패·누락 시간대 재처리용)
-- status: processing | success | failed(재시도 대기) | dead(재시도 한도 초과 → 알림 대상)
CREATE TABLE IF NOT EXISTS gharchive_hour_status (
    hour_bucket   TIMESTAMP PRIMARY KEY,          -- UTC 시간대
    status        VARCHAR(16) NOT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    last_error    TEXT,
    next_retry_at TIMESTAMP,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    duration_ms   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_gharchive_hour_retry ON gharchive_hour_status (next_retry_at) WHERE status = 'failed';

-- 테이블 도입 전에 처리된 시간대를 성공으로 등록 (최초 1회: 테이블이 비어 있을 때만)
INSERT INTO gharchive_hour_status (hour_bucket, status, attempts, finished_at)
SELECT DISTINCT bucket, 'success', 1, now() AT TIME ZONE 'UTC' FROM repo_star_hourly
WHERE NOT EXISTS (SELECT 1 FROM gharchive_hour_status)
ON CONFLICT DO NOTHING;

-- 정시 작업 분산 락 (ShedLock): 작업 이름별 1행, lock_until이 지나야 다른 인스턴스가 실행권을 가져감
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
