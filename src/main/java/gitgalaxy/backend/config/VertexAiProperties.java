package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vertexai")
@Data
public class VertexAiProperties {

    private String project = "galaxy-498305";
    private String location = "us-central1";
    private String model = "gemini-2.5-flash";
    private String embeddingModel = "text-embedding-004";

    /** 신규 repo 온보딩 시 README AI 요약 생성 여부 */
    private boolean summaryEnabled = true;

    /** 임베딩 API 동시 호출 수 (청크 임베딩·레포 임베딩) */
    private int embeddingConcurrency = 4;
}
