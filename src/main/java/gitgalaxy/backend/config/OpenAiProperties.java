package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "openai")
@Data
public class OpenAiProperties {

    private String apiKey = "";
    private String embeddingModel = "text-embedding-3-small";
    private String chatModel = "gpt-4o-mini";
}
