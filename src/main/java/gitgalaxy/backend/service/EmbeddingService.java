package gitgalaxy.backend.service;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.EndpointName;
import com.google.cloud.vertexai.api.PredictResponse;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import gitgalaxy.backend.config.VertexAiProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class EmbeddingService {

    static final int DIMS = 768;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final VertexAiProperties props;
    private VertexAI vertexAI;
    private PredictionServiceClient client;
    private String endpointName;

    public EmbeddingService(VertexAiProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        vertexAI = new VertexAI(props.getProject(), props.getLocation());
        client = vertexAI.getPredictionServiceClient();
        endpointName = EndpointName.ofProjectLocationPublisherModelName(
                props.getProject(), props.getLocation(), "google", props.getEmbeddingModel()
        ).toString();
        log.info("EmbeddingService 초기화: model={}", props.getEmbeddingModel());
    }

    @PreDestroy
    public void destroy() {
        try {
            if (client != null) client.close();
            if (vertexAI != null) vertexAI.close();
        } catch (Exception e) {
            log.warn("EmbeddingService 종료 오류: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return props.getProject() != null && !props.getProject().isBlank();
    }

    public float[] embed(String text) {
        String input = text.length() > 8000 ? text.substring(0, 8000) : text;

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return doEmbed(input);
            } catch (Exception e) {
                lastException = e;
                log.warn("임베딩 시도 {}/{} 실패: {}", attempt + 1, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Embedding 인터럽트", ie);
                    }
                    reconnect();
                }
            }
        }
        throw new RuntimeException("Embedding 실패: " + lastException.getMessage(), lastException);
    }

    public List<float[]> embedBatch(List<String> texts) {
        List<Value> instances = texts.stream()
                .map(text -> {
                    String input = text.length() > 8000 ? text.substring(0, 8000) : text;
                    return Value.newBuilder()
                            .setStructValue(Struct.newBuilder()
                                    .putFields("content", Value.newBuilder().setStringValue(input).build())
                                    .build())
                            .build();
                })
                .toList();

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                PredictResponse response = client.predict(endpointName, instances, Value.newBuilder().build());
                List<float[]> result = new ArrayList<>();
                for (int i = 0; i < response.getPredictionsCount(); i++) {
                    ListValue values = response.getPredictions(i)
                            .getStructValue()
                            .getFieldsOrThrow("embeddings")
                            .getStructValue()
                            .getFieldsOrThrow("values")
                            .getListValue();
                    float[] vector = new float[values.getValuesCount()];
                    for (int j = 0; j < values.getValuesCount(); j++) {
                        vector[j] = (float) values.getValues(j).getNumberValue();
                    }
                    result.add(vector);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("배치 임베딩 시도 {}/{} 실패: {}", attempt + 1, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Embedding 인터럽트", ie);
                    }
                    reconnect();
                }
            }
        }
        throw new RuntimeException("배치 Embedding 실패: " + lastException.getMessage(), lastException);
    }

    private float[] doEmbed(String input) {
        Value instance = Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                        .putFields("content", Value.newBuilder().setStringValue(input).build())
                        .build())
                .build();

        PredictResponse response = client.predict(endpointName, List.of(instance), Value.newBuilder().build());

        ListValue values = response.getPredictions(0)
                .getStructValue()
                .getFieldsOrThrow("embeddings")
                .getStructValue()
                .getFieldsOrThrow("values")
                .getListValue();

        float[] vector = new float[values.getValuesCount()];
        for (int i = 0; i < values.getValuesCount(); i++) {
            vector[i] = (float) values.getValues(i).getNumberValue();
        }
        return vector;
    }

    private void reconnect() {
        log.info("gRPC 재연결 시도...");
        try {
            if (client != null) client.close();
            if (vertexAI != null) vertexAI.close();
        } catch (Exception ignored) {}
        vertexAI = new VertexAI(props.getProject(), props.getLocation());
        client = vertexAI.getPredictionServiceClient();
    }

    public static String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
