package gitgalaxy.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * 문서 파일의 heading 단위 청크 (JSONL 한 줄 = 하나의 ChunkDocument)
 */
@Data
@Builder
public class ChunkDocument {

    @JsonProperty("repo_owner")
    private String repoOwner;

    @JsonProperty("repo_name")
    private String repoName;

    @JsonProperty("file_path")
    private String filePath;

    @JsonProperty("chunk_id")
    private String chunkId;

    @JsonProperty("chunk_index")
    private int chunkIndex;

    /** 속한 heading 텍스트 (없으면 "(intro)") */
    private String heading;

    private String content;

    /** GitHub 파일 URL */
    private String url;
}
