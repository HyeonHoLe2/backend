package gitgalaxy.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final GeminiClient geminiClient;

    public String chat(String prompt) {
        try {
            return geminiClient.generate(prompt);
        } catch (Exception e) {
            throw new RuntimeException("LLM 요청 실패: " + e.getMessage(), e);
        }
    }
}
