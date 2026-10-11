package gitgalaxy.backend.service;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Candidate;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import gitgalaxy.backend.config.VertexAiProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gemini 공유 클라이언트. 요청마다 VertexAI(인증·gRPC 채널·스레드 풀)를 새로 만들던 것을 앱 전체에서 1개로 재사용.
 * - 호출마다 제한 시간: 망가진 연결에서 무한 대기하지 않도록 (초과 시 에러 + 재연결)
 * - 연결 오류(UNAVAILABLE 등)면 클라이언트 교체: 동시에 여러 스레드가 실패해도 교체는 1번 (실패한 그 클라이언트일 때만)
 * - 앱 종료 시 정리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    private static final long TIMEOUT_SECONDS = 60;

    private final VertexAiProperties props;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gemini-call");
        t.setDaemon(true);
        return t;
    });

    private volatile VertexAI vertexAI;
    private volatile GenerativeModel model;

    /**
     * 응답 텍스트 반환. 후보·본문이 없는 응답(안전 필터 차단, 출력 한도 도달 등)은 예외 대신 null + 원인 로그
     * → 호출 측이 "답변 없음"으로 처리.
     */
    public String generate(String prompt) {
        GenerativeModel m = model();
        Future<GenerateContentResponse> future = executor.submit(() -> m.generateContent(prompt));
        try {
            return textOf(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            reconnect(m);
            throw new RuntimeException("Gemini 응답 " + TIMEOUT_SECONDS + "초 초과 (UNAVAILABLE로 간주)", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (isConnectionError(cause)) reconnect(m);
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini 호출 인터럽트", e);
        }
    }

    /** 첫 후보의 텍스트 조각을 모두 이어 붙임 (여러 조각으로 오는 응답이 잘리지 않도록). 없으면 null */
    private static String textOf(GenerateContentResponse response) {
        if (response.getCandidatesCount() == 0) {
            log.warn("Gemini 빈 응답: 후보 없음 (차단 사유 {})", response.getPromptFeedback().getBlockReason());
            return null;
        }
        Candidate candidate = response.getCandidates(0);
        StringBuilder sb = new StringBuilder();
        for (Part part : candidate.getContent().getPartsList()) {
            if (part.hasText()) sb.append(part.getText());
        }
        if (sb.isEmpty()) {
            log.warn("Gemini 빈 응답: 본문 없음 (종료 사유 {})", candidate.getFinishReason());
            return null;
        }
        return sb.toString();
    }

    private GenerativeModel model() {
        GenerativeModel m = model;
        if (m != null) return m;
        synchronized (this) {
            if (model == null) open();
            return model;
        }
    }

    private void open() {
        vertexAI = new VertexAI(props.getProject(), props.getLocation());
        model = new GenerativeModel(props.getModel(), vertexAI);
    }

    /** 실패한 호출이 쓰던 클라이언트가 아직 현재 것일 때만 교체 (다른 스레드가 이미 교체했으면 그대로 사용) */
    private synchronized void reconnect(GenerativeModel failed) {
        if (failed != model) return;
        log.info("Gemini 클라이언트 재연결");
        closeQuietly();
        open();
    }

    private static boolean isConnectionError(Throwable t) {
        for (; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("UNAVAILABLE") || m.contains("io exception") || m.contains("DEADLINE_EXCEEDED"))) {
                return true;
            }
        }
        return false;
    }

    private void closeQuietly() {
        try {
            if (vertexAI != null) vertexAI.close();
        } catch (Exception ignored) {}
    }

    @PreDestroy
    void shutdown() {
        closeQuietly();
        executor.shutdownNow();
    }
}
