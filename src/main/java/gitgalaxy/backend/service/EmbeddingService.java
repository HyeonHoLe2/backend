package gitgalaxy.backend.service;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentResponse;
import gitgalaxy.backend.config.GeminiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gemini text-embedding-004 SDK 호출 → float[] 반환.
 * 768 차원 고정 (pgvector 스키마와 일치).
 */
@Service
@Slf4j
public class EmbeddingService {

    static final int DIMS = 3072;

    private final GeminiProperties props;

    public EmbeddingService(GeminiProperties props) {
        this.props = props;
    }

    public boolean isConfigured() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    public float[] embed(String text) {
        try {
            String input = text.length() > 8000 ? text.substring(0, 8000) : text;

            Client client = Client.builder()
                    .apiKey(props.getApiKey())
                    .build();

            EmbedContentResponse response = client.models.embedContent(
                    props.getEmbeddingModel(),
                    input,
                    null
            );

            List<Float> values = response.embeddings()
                    .orElseThrow(() -> new RuntimeException("임베딩 결과 없음"))
                    .get(0)
                    .values()
                    .orElseThrow(() -> new RuntimeException("임베딩 값 없음"));

            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i);
            }
            return vector;

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
