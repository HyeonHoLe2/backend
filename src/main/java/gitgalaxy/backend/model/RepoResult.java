package gitgalaxy.backend.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * repo 단위 수집 결과 (batch_summary에 기록됨)
 */
@Data
@Builder
public class RepoResult {

    private String owner;
    private String repo;

    /** success | failed | skipped */
    private String status;

    private int fileCount;
    private int chunkCount;
    private long durationMs;

    /** 실패 시 에러 메시지 */
    private String errorMessage;

    private LocalDateTime processedAt;

    public String fullName() {
        return owner + "/" + repo;
    }
}
