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
}
