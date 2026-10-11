package gitgalaxy.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GithubCollectorProperties;
import gitgalaxy.backend.metrics.CollectorMetrics;
import gitgalaxy.backend.model.PushCommits;
import gitgalaxy.backend.model.RepoMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
    private static final long HARD_TIMEOUT_SECONDS = 60;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GithubCollectorProperties props;
    private final CollectorMetrics metrics;

    // 마지막 API 응답 기준 rate limit 상태 (-1 = 아직 모름)
    private final AtomicLong rateLimitRemaining = new AtomicLong(-1);
    private final AtomicLong rateLimitResetEpochSec = new AtomicLong(0);

    public GithubClient(ObjectMapper objectMapper, GithubCollectorProperties props, CollectorMetrics metrics) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.metrics = metrics;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // 절전 복귀 후 죽은 HTTP/2 연결에 요청이 묶여 무한 대기한 사례 → HTTP/1.1 사용
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────


    /** 메타 + GitHub 기준 현재 이름. 이름 변경·이전된 repo(301)는 이동한 주소를 한 번 따라가 새 이름을 받음 */
    public record RepoMetaWithName(String fullName, RepoMeta meta) {}

    public RepoMetaWithName getRepoMetaFollowingMoves(String owner, String repo) {
        String url = API_BASE + "/repos/" + owner + "/" + repo;
        String body;
        try {
            body = executeApiGet(url);
        } catch (GithubMovedException moved) {
            body = executeApiGet(moved.getLocation());
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return new RepoMetaWithName(node.path("full_name").asText(owner + "/" + repo), parseRepoMeta(node));
        } catch (Exception e) {
            throw new RuntimeException("RepoMeta 파싱 실패: " + url, e);
        }
    }

    /** repo 메타 정보 반환 (defaultBranch, description, stargazersCount) */
    public RepoMeta getRepoMeta(String owner, String repo) {
        String url = API_BASE + "/repos/" + owner + "/" + repo;
        String body = executeApiGet(url);
        try {
            return parseRepoMeta(objectMapper.readTree(body));
        } catch (Exception e) {
            throw new RuntimeException("RepoMeta 파싱 실패: " + url, e);
        }
    }

    private RepoMeta parseRepoMeta(JsonNode node) {
        String language = node.path("language").isNull() ? null : node.path("language").asText(null);
        return new RepoMeta(
                node.path("default_branch").asText("main"),
                node.path("description").asText(""),
                node.path("stargazers_count").asInt(0),
                node.path("forks_count").asInt(0),
                node.path("open_issues_count").asInt(0),
                language
        );
    }

    /** repo의 default branch 이름 반환 */
    public String getDefaultBranch(String owner, String repo) {
        return getRepoMeta(owner, repo).defaultBranch();
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

    /**
     * push 1건(before → head)에 포함된 커밋 조회.
     * GH Archive PushEvent payload에는 commits가 없어 compare API로 보완.
     * 새 브랜치 push(before = 0000…)는 head 커밋 1개만 조회.
     */
    /** push 1건의 커밋을 REST compare로 조회 (GraphQL 이력에서 before를 찾지 못한 push의 보완용) */
    public PushCommits getPushCommits(String owner, String repo, String before, String head) {
        boolean newBranch = before == null || before.isBlank() || before.chars().allMatch(c -> c == '0');
        String url = newBranch
                ? API_BASE + "/repos/" + owner + "/" + repo + "/commits/" + head
                : API_BASE + "/repos/" + owner + "/" + repo + "/compare/" + before + "..." + head;
        String body = executeApiGet(url);
        try {
            JsonNode node = objectMapper.readTree(body);
            List<PushCommits.Commit> commits = new ArrayList<>();
            if (newBranch) {
                commits.add(new PushCommits.Commit(node.path("sha").asText(), node.path("commit").path("message").asText("")));
                return new PushCommits(1, commits);
            }
            for (JsonNode c : node.path("commits")) {
                commits.add(new PushCommits.Commit(c.path("sha").asText(), c.path("commit").path("message").asText("")));
            }
            return new PushCommits(node.path("total_commits").asInt(commits.size()), commits);
        } catch (Exception e) {
            throw new RuntimeException("커밋 파싱 실패: " + url, e);
        }
    }

    /** 커밋 조회 대상 push 1건 */
    public record PushQuery(String owner, String name, String before, String head) {}

    /** 묶음 조회 결과: 입력 순서대로 (조회 불가 = null) + 이번 요청의 GraphQL 비용·남은 포인트 */
    public record PushCommitsBatch(List<PushCommits> results, int cost, int remaining) {}

    /**
     * push 여러 건의 커밋을 GraphQL 1회로 조회 (push마다 REST compare 1회 → 묶음당 1회).
     * head 커밋에서 이력을 거슬러 올라가 before 커밋 직전까지를 이번 push의 커밋으로 봄.
     * - 새 브랜치(before = 0000…)는 head 커밋 1개만 (기존 REST 방식과 동일)
     * - depth 안에 before가 없으면(커밋이 아주 많음, 머지 커밋으로 다른 브랜치 이력이 섞임, force push)
     *   정확한 범위를 알 수 없으므로 totalCommits = -1 → 호출 측에서 REST compare로 보완
     * - 커밋이 지워졌거나 repo가 없으면 해당 push만 null
     */
    public PushCommitsBatch getPushCommitsBatch(List<PushQuery> pushes, int depth) {
        StringBuilder q = new StringBuilder("query { rateLimit { cost remaining }");
        for (int i = 0; i < pushes.size(); i++) {
            PushQuery p = pushes.get(i);
            if (!p.head().matches("[0-9a-f]{40}")) continue;
            q.append(" p").append(i).append(": repository(owner: ").append(gqlString(p.owner()))
             .append(", name: ").append(gqlString(p.name())).append(") { object(oid: \"").append(p.head())
             .append("\") { ... on Commit { history(first: ").append(depth).append(") { nodes { oid message } } } } }");
        }
        q.append(" }");
        JsonNode data = graphql(q.toString());

        List<PushCommits> results = new ArrayList<>(pushes.size());
        for (int i = 0; i < pushes.size(); i++) {
            JsonNode nodes = data.path("p" + i).path("object").path("history").path("nodes");
            if (!nodes.isArray() || nodes.isEmpty()) {
                results.add(null);
                continue;
            }
            PushQuery p = pushes.get(i);
            boolean newBranch = p.before() == null || p.before().isBlank() || p.before().chars().allMatch(c -> c == '0');
            List<PushCommits.Commit> commits = new ArrayList<>();
            boolean reachedBefore = newBranch;
            for (JsonNode n : nodes) {
                String oid = n.path("oid").asText();
                if (oid.equals(p.before())) { reachedBefore = true; break; }
                commits.add(new PushCommits.Commit(oid, n.path("message").asText("")));
                if (newBranch) break;
            }
            results.add(reachedBefore ? new PushCommits(commits.size(), commits) : new PushCommits(-1, List.of()));
        }
        JsonNode rl = data.path("rateLimit");
        return new PushCommitsBatch(results, rl.path("cost").asInt(0), rl.path("remaining").asInt(-1));
    }

    private String gqlString(String s) {
        try {
            return objectMapper.writeValueAsString(s); // JSON 문자열 이스케이프 = GraphQL 문자열 리터럴
        } catch (Exception e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    /**
     * GraphQL 질의. REST와 별도 한도(시간당 5,000포인트)를 쓰므로 REST 여유분(hasBudget) 계산에 넣지 않음.
     * 일부 alias가 NOT_FOUND여도 data는 반환되므로 errors는 경고만 남기고 data를 돌려줌.
     */
    public JsonNode graphql(String query) {
        String body;
        try {
            body = objectMapper.writeValueAsString(java.util.Map.of("query", query));
        } catch (Exception e) {
            throw new RuntimeException("GraphQL 요청 직렬화 실패", e);
        }
        String url = API_BASE + "/graphql";
        String response = executeWithRetry(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + props.getToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = send(request, url);
            if (res.statusCode() == 200) return res.body();
            if (isRateLimited(res)) {
                long sleepMs = getRateLimitSleepMs(res);
                log.warn("GraphQL rate limited (HTTP {}). {}ms 대기 후 재시도...", res.statusCode(), sleepMs);
                Thread.sleep(sleepMs);
                throw new RuntimeException("GraphQL rate limited: HTTP " + res.statusCode());
            }
            throw new GithubApiException(res.statusCode(), url);
        });
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("errors")) {
                log.debug("GraphQL 부분 오류: {}", root.get("errors").toString());
            }
            return root.path("data");
        } catch (Exception e) {
            throw new RuntimeException("GraphQL 응답 파싱 실패", e);
        }
    }

    /** raw 파일 내용 다운로드 */
    public String getRawContent(String owner, String repo, String branch, String filePath) {
        String encodedPath = Arrays.stream(filePath.split("/", -1))
                .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        String url = RAW_BASE + "/" + owner + "/" + repo + "/" + branch + "/" + encodedPath;
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

            HttpResponse<String> response = send(request, url);
            updateRateLimit(response);

            // 성공 시 rate limit 잔여량 확인 → 소진 직전이면 선제 sleep
            if (response.statusCode() == 200) {
                proactiveRateLimitSleep(response);
                return response.body();
            }

            // rate limit 초과 (403은 잔여량 0일 때만 rate limit, 나머지는 권한 오류)
            if (isRateLimited(response)) {
                long sleepMs = getRateLimitSleepMs(response);
                log.warn("Rate limited (HTTP {}). {}ms 대기 후 재시도...", response.statusCode(), sleepMs);
                Thread.sleep(sleepMs);
                throw new RuntimeException("Rate limited: HTTP " + response.statusCode());
            }

            // 이름 변경·이전된 repo: GitHub가 301 + Location(/repositories/{id})으로 알려줌
            if (response.statusCode() == 301 || response.statusCode() == 307) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null) throw new GithubMovedException(response.statusCode(), url, location);
            }

            throw new GithubApiException(response.statusCode(), url);
        });
    }

    private String executeRawGet(String url) {
        return executeWithRetry(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = send(request, url);

            if (response.statusCode() >= 400) {
                throw new GithubApiException(response.statusCode(), url);
            }
            return response.body();
        });
    }

    // ────────────────────────────────────────────────
    // Retry & Rate limit helpers
    // ────────────────────────────────────────────────

    /**
     * 수집용 호출을 계속해도 되는지. 남은 요청 수가 여유분(rateLimitReserve) 이하이면 리셋 전까지 false.
     * 아직 응답을 한 번도 못 받았거나 리셋 시각이 지났으면 true.
     */
    public boolean hasBudget() {
        long remaining = rateLimitRemaining.get();
        if (remaining < 0) return true;
        if (System.currentTimeMillis() / 1000 >= rateLimitResetEpochSec.get()) return true;
        return remaining > props.getRateLimitReserve();
    }

    public long getRateLimitRemaining() {
        return rateLimitRemaining.get();
    }

    private void updateRateLimit(HttpResponse<String> response) {
        response.headers().firstValue("X-RateLimit-Remaining").ifPresent(r -> {
            long remaining = Long.parseLong(r);
            rateLimitRemaining.set(remaining);
            metrics.githubRateLimitRemaining(remaining);
        });
        response.headers().firstValue("X-RateLimit-Reset")
                .ifPresent(r -> rateLimitResetEpochSec.set(Long.parseLong(r)));
    }

    /**
     * request.timeout()이 동작하지 않고 send()가 무한 대기한 사례(절전 복귀 후 17시간 정지)가 있어
     * 바깥에서 한 번 더 시간 제한. 초과 시 요청 취소 후 TimeoutException → 재시도 대상.
     */
    private HttpResponse<String> sendWithHardTimeout(HttpRequest request) throws Exception {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(HARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("GitHub 요청 " + HARD_TIMEOUT_SECONDS + "초 초과: " + request.uri());
        }
    }

    /** 요청 1회 전송 + 지표 기록 (endpoint·status별 횟수와 소요 시간) */
    private HttpResponse<String> send(HttpRequest request, String url) throws Exception {
        long start = System.nanoTime();
        String endpoint = endpointOf(url);
        try {
            HttpResponse<String> response = sendWithHardTimeout(request);
            metrics.githubRequest(endpoint, String.valueOf(response.statusCode()), start);
            return response;
        } catch (Exception e) {
            metrics.githubRequest(endpoint, "error", start);
            throw e;
        }
    }

    private static String endpointOf(String url) {
        if (url.startsWith(RAW_BASE)) return "raw";
        if (url.contains("/compare/")) return "compare";
        if (url.contains("/commits/")) return "commit";
        if (url.contains("/git/trees/")) return "tree";
        if (url.startsWith(API_BASE + "/repos/")) return "repo";
        if (url.equals(API_BASE + "/graphql")) return "graphql";
        return "other";
    }

    /** GitHub 응답 오류. 4xx는 재시도해도 결과가 같으므로 즉시 실패 */
    public static class GithubApiException extends RuntimeException {
        private final int status;

        public GithubApiException(int status, String url) {
            super("GitHub 오류: HTTP " + status + " → " + url);
            this.status = status;
        }

        public int getStatus() { return status; }

        public boolean isNotFound() { return status == 404 || status == 451; }

        boolean isRetryable() { return status >= 500; }
    }

    /** repo가 다른 이름·소유자로 옮겨짐 (301/307). location = 이동한 repo의 API 주소 */
    public static class GithubMovedException extends GithubApiException {
        private final String location;

        public GithubMovedException(int status, String url, String location) {
            super(status, url);
            this.location = location;
        }

        public String getLocation() { return location; }
    }

    private boolean isRateLimited(HttpResponse<String> response) {
        if (response.statusCode() == 429) return true;
        if (response.statusCode() != 403) return false;
        return response.headers().firstValue("X-RateLimit-Remaining").map("0"::equals).orElse(false)
                || response.headers().firstValue("Retry-After").isPresent();
    }

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
            } catch (GithubApiException e) {
                if (!e.isRetryable()) throw e;
                lastException = e;
                sleepBeforeRetry(attempt, e);
            } catch (Exception e) {
                lastException = e;
                sleepBeforeRetry(attempt, e);
            }
        }
        throw new RuntimeException("최대 재시도(" + props.getMaxRetries() + "회) 초과", lastException);
    }

    private void sleepBeforeRetry(int attempt, Exception e) {
        if (attempt >= props.getMaxRetries() - 1) return;
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
