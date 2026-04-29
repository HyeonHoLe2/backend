package gitgalaxy.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.OpenAiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI text-embedding-3-small API 호출 → float[] 반환.
 * 1536 차원 고정 (pgvector 스키마와 일치).
 */
@Service
@Slf4j
public class EmbeddingService {

    private static final String EMBEDDING_URL = "https://api.openai.com/v1/embeddings";
    static final int DIMS = 1536;

    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmbeddingService(OpenAiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public boolean isConfigured() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    public float[] embed(String text) {
        try {
            // OpenAI 토큰 한도 초과 방지 (≈ 8191 tokens)
            String input = text.length() > 8000 ? text.substring(0, 8000) : text;

            String body = objectMapper.writeValueAsString(
                    Map.of("model", props.getEmbeddingModel(), "input", input));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EMBEDDING_URL))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode embeddingNode = objectMapper.readTree(response.body())
                    .path("data").get(0).path("embedding");

            float[] vector = new float[DIMS];
            for (int i = 0; i < DIMS; i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Embedding 인터럽트", e);
        } catch (Exception e) {
            throw new RuntimeException("Embedding 실패: " + e.getMessage(), e);
        }
    }

    /** float[] → pgvector 문자열 형식 "[0.1,0.2,...]" */
    public static String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
