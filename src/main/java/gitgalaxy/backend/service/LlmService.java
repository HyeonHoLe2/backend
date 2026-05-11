package gitgalaxy.backend.service;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import gitgalaxy.backend.config.VertexAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final VertexAiProperties vertexAiProperties;

    public String chat(String prompt) {
        try (VertexAI vertexAI = new VertexAI(vertexAiProperties.getProject(), vertexAiProperties.getLocation())) {
            GenerativeModel model = new GenerativeModel(vertexAiProperties.getModel(), vertexAI);
            GenerateContentResponse response = model.generateContent(prompt);
            return response.getCandidates(0).getContent().getParts(0).getText();
        } catch (Exception e) {
            throw new RuntimeException("LLM 요청 실패: " + e.getMessage(), e);
        }
    }
}
