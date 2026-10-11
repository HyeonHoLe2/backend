package gitgalaxy.backend;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import gitgalaxy.backend.config.VertexAiProperties;
import gitgalaxy.backend.service.GeminiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini 클라이언트 생성 비용 측정: 요청마다 새로 생성 vs 공유 클라이언트.
 * 짧은 프롬프트로 생성 시간을 최소화해 클라이언트 준비(인증·채널) 비용을 드러냄. 두 방식을 번갈아 호출해 시간대 편차를 상쇄.
 * 실행: RUN_BENCH=true ./gradlew test --tests GeminiClientBenchmarkTest
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "true")
class GeminiClientBenchmarkTest {

    private static final int ROUNDS = 10;
    private static final String PROMPT = "Reply with exactly one word: ok";

    @Autowired private GeminiClient geminiClient;
    @Autowired private VertexAiProperties props;

    @Test
    void compare() throws Exception {
        geminiClient.generate(PROMPT); // 공유 클라이언트 최초 생성은 측정에서 제외 (앱 기동 후 1회만 발생)
        List<Long> perRequest = new ArrayList<>(), shared = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            long t0 = System.nanoTime();
            try (VertexAI vertexAI = new VertexAI(props.getProject(), props.getLocation())) {
                new GenerativeModel(props.getModel(), vertexAI).generateContent(PROMPT);
            }
            perRequest.add((System.nanoTime() - t0) / 1_000_000);

            long t1 = System.nanoTime();
            geminiClient.generate(PROMPT);
            shared.add((System.nanoTime() - t1) / 1_000_000);
        }
        System.out.printf("%n[요청마다 생성] %s → 중앙값 %d ms, 평균 %d ms%n", perRequest, median(perRequest), avg(perRequest));
        System.out.printf("[공유 클라이언트] %s → 중앙값 %d ms, 평균 %d ms%n", shared, median(shared), avg(shared));
    }

    private static long median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        s.sort(null);
        return (s.get(s.size() / 2) + s.get((s.size() - 1) / 2)) / 2;
    }

    private static long avg(List<Long> v) {
        return Math.round(v.stream().mapToLong(Long::longValue).average().orElse(0));
    }
}
