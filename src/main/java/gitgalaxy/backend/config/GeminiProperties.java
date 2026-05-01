package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini")
@Data
public class GeminiProperties {

    /** GEMINI_API_KEY 또는 gemini.api-key */
    private String apiKey = "";

    /** https://ai.google.dev/gemini-api/docs/models */
    private String model = "gemini-2.5-flash";

    /** 503/429 등 재시도 최대 횟수 */
    private int maxRetries = 5;

    /** 첫 재시도 전 대기(ms), 이후 2배씩(상한 60초) */
    private long retryDelayMs = 2000;
}
