package gitgalaxy.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GithubCollectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub API / raw content 호출 담당.
 * - java.net.http.HttpClient 사용 (응답 헤더 직접 접근 가능)
 * - 3회 retry + exponential backoff
 * - rate limit(403/429) 감지 시 X-RateLimit-Reset 기준으로 sleep
 */
@Service
@Slf4j
public class GithubClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GithubCollectorProperties props;

    public GithubClient(ObjectMapper objectMapper, GithubCollectorProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────

    /** repo의 default branch 이름 반환 */
    public String getDefaultBranch(String owner, String repo) {
        String url = API_BASE + "/repos/" + owner + "/" + repo;
        String body = executeApiGet(url);
        try {
            return objectMapper.readTree(body).get("default_branch").asText();
        } catch (Exception e) {
            throw new RuntimeException("default_branch 파싱 실패: " + url, e);
        }
    }

    /** recursive tree 조회 → blob 파일 경로 목록 반환 */
    public List<String> getRepoTree(String owner, String repo, String branch) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1";
        String body = executeApiGet(url);
        try {
            JsonNode tree = objectMapper.readTree(body).get("tree");
            List<String> paths = new ArrayList<>();
            if (tree != null && tree.isArray()) {
                for (JsonNode item : tree) {
                    if ("blob".equals(item.path("type").asText())) {
                        paths.add(item.path("path").asText());
                    }
                }
            }
            return paths;
        } catch (Exception e) {
            throw new RuntimeException("tree 파싱 실패: " + url, e);
        }
    }

    /** raw 파일 내용 다운로드 */
    public String getRawContent(String owner, String repo, String branch, String filePath) {
        // filePath에 공백/특수문자가 있을 경우 URL encode 필요할 수 있으나 대부분의 docs는 안전
        String url = RAW_BASE + "/" + owner + "/" + repo + "/" + branch + "/" + filePath;
        return executeRawGet(url);
    }

    // ────────────────────────────────────────────────
    // Internal HTTP execution
    // ────────────────────────────────────────────────

    private String executeApiGet(String url) {
        return executeWithRetry(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + props.getToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 성공 시 rate limit 잔여량 확인 → 소진 직전이면 선제 sleep
            if (response.statusCode() == 200) {
                proactiveRateLimitSleep(response);
                return response.body();
            }

            // rate limit 초과
            if (response.statusCode() == 403 || response.statusCode() == 429) {
                long sleepMs = getRateLimitSleepMs(response);
                log.warn("Rate limited (HTTP {}). {}ms 대기 후 재시도...", response.statusCode(), sleepMs);
                Thread.sleep(sleepMs);
                throw new RuntimeException("Rate limited: HTTP " + response.statusCode());
            }

            throw new RuntimeException("GitHub API 오류: HTTP " + response.statusCode() + " → " + url);
        });
    }

    private String executeRawGet(String url) {
        return executeWithRetry(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("Raw content 오류: HTTP " + response.statusCode() + " → " + url);
            }
            return response.body();
        });
    }

    // ────────────────────────────────────────────────
    // Retry & Rate limit helpers
    // ────────────────────────────────────────────────

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private <T> T executeWithRetry(CheckedSupplier<T> supplier) {
        Exception lastException = null;
        for (int attempt = 0; attempt < props.getMaxRetries(); attempt++) {
            try {
                return supplier.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during retry", ie);
            } catch (Exception e) {
                lastException = e;
                if (attempt < props.getMaxRetries() - 1) {
                    long delay = props.getRetryDelayMs() * (1L << attempt); // 1s → 2s → 4s
                    log.warn("시도 {}/{} 실패 ({}). {}ms 후 재시도...",
                            attempt + 1, props.getMaxRetries(), e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry wait", ie);
                    }
                }
            }
        }
        throw new RuntimeException("최대 재시도(" + props.getMaxRetries() + "회) 초과", lastException);
    }

    /** 성공 응답에서 X-RateLimit-Remaining 이 낮으면 reset 시간까지 선제 sleep */
    private void proactiveRateLimitSleep(HttpResponse<String> response) {
        response.headers().firstValue("X-RateLimit-Remaining").ifPresent(remaining -> {
            if (Integer.parseInt(remaining) <= 5) {
                long sleepMs = getRateLimitSleepMs(response);
                log.warn("Rate limit 잔여량={}. Reset까지 {}ms 선제 대기...", remaining, sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /** X-RateLimit-Reset 헤더 기반 sleep 시간 계산 (없으면 기본 60초) */
    private long getRateLimitSleepMs(HttpResponse<String> response) {
        return response.headers().firstValue("X-RateLimit-Reset")
                .map(reset -> {
                    long resetMs = Long.parseLong(reset) * 1000L;
                    return Math.max(1000L, resetMs - System.currentTimeMillis() + 1000L);
                })
                .orElse(60_000L);
    }
}
