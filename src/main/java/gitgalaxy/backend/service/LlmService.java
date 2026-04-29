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
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions API 호출.
 * 단일 user 메시지 → 응답 텍스트 반환.
 */
@Service
@Slf4j
public class LlmService {

    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmService(OpenAiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public String chat(String prompt) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY가 설정되지 않았습니다.");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", props.getChatModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 1000,
                    "temperature", 0.3
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CHAT_URL))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }

            return objectMapper.readTree(response.body())
                    .path("choices").get(0)
                    .path("message").path("content").asText();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 요청 인터럽트", e);
        } catch (Exception e) {
            throw new RuntimeException("LLM 요청 실패: " + e.getMessage(), e);
        }
    }
}
