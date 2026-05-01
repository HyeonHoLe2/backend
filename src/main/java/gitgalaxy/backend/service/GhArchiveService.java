package gitgalaxy.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GhArchiveProperties;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class GhArchiveService {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-H");

    // metrics 배열 인덱스
    private static final int W_WATCH    = 0;
    private static final int W_COMMIT   = 1;
    private static final int W_PR_OPEN  = 2;
    private static final int W_PR_MERGE = 3;
    private static final int W_IS_OPEN  = 4;
    private static final int W_IS_CLOSE = 5;
    private static final int W_STAR     = 6;
    private static final int W_RELEASE  = 7;

    private final GhArchiveProperties props;
    private final RepoRepository repoRepository;
    private final RepoHourlyMetricsRepository metricsRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 특정 시간의 GH Archive 파일 처리.
     * - 추적 repo 이벤트 집계 → repo_time upsert
     * - commit message / PR body / issue body → ChunkDocument 반환 (임베딩용)
     * - WatchEvent ≥ threshold인 미추적 repo → repos 등록
     */
    public List<ChunkDocument> processHour(LocalDateTime bucket) {
        String fileName = bucket.format(HOUR_FMT) + ".json.gz";
        String url = props.getBaseUrl() + "/" + fileName;
        log.info("GH Archive 처리 시작: {}", url);

        Set<String> trackedRepos = repoRepository.findByTrackedTrue()
                .stream()
                .map(r -> r.getOwner() + "/" + r.getName())
                .collect(Collectors.toSet());

        // fullName → int[8]: watch, commit, prOpen, prMerge, issueOpen, issueClose, star, release
        Map<String, int[]> metrics = new HashMap<>();
        Map<String, Integer> watchCounts = new HashMap<>(); // 발견용 (전체 대상)
        List<ChunkDocument> chunks = new ArrayList<>();

        long lineCount = 0;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "GitGalaxy/1.0")
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.warn("GH Archive 파일 없음 (HTTP {}): {}", resp.statusCode(), url);
                return List.of();
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(resp.body())))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    processEvent(line, trackedRepos, metrics, watchCounts, chunks);
                }
            }
        } catch (Exception e) {
            log.error("GH Archive 처리 실패 {}: {}", url, e.getMessage(), e);
            return List.of();
        }

        log.info("GH Archive {}: {}줄, metrics {}개 repo, chunks {}개", fileName, lineCount, metrics.size(), chunks.size());

        // 추적 repo 메트릭 upsert
        for (Map.Entry<String, int[]> entry : metrics.entrySet()) {
            String[] parts = entry.getKey().split("/", 2);
            int[] m = entry.getValue();
            try {
                metricsRepository.upsert(parts[0], parts[1], bucket,
                        m[W_WATCH], m[W_COMMIT], m[W_PR_OPEN], m[W_PR_MERGE],
                        m[W_IS_OPEN], m[W_IS_CLOSE], m[W_STAR], m[W_RELEASE]);
            } catch (Exception e) {
                log.warn("repo_time upsert 실패 {}: {}", entry.getKey(), e.getMessage());
            }
        }

        // WatchEvent ≥ threshold인 미추적 repo 등록
        int discovered = 0;
        for (Map.Entry<String, Integer> entry : watchCounts.entrySet()) {
            String fullName = entry.getKey();
            if (!trackedRepos.contains(fullName) && entry.getValue() >= props.getMinWatchCountForDiscovery()) {
                String[] parts = fullName.split("/", 2);
                if (parts.length != 2) continue;
                try {
                    repoRepository.findByFullName(fullName).orElseGet(() ->
                            repoRepository.save(Repo.builder()
                                    .fullName(fullName).owner(parts[0]).name(parts[1])
                                    .tracked(false).createdAt(LocalDateTime.now()).build()));
                    discovered++;
                } catch (Exception e) {
                    log.warn("신규 repo 등록 실패 {}: {}", fullName, e.getMessage());
                }
            }
        }
        if (discovered > 0) log.info("GH Archive: 신규 repo 후보 {}개 (watch≥{})", discovered, props.getMinWatchCountForDiscovery());

        return chunks;
    }

    // ─── private ──────────────────────────────────────────

    private void processEvent(String line, Set<String> trackedRepos,
                              Map<String, int[]> metrics, Map<String, Integer> watchCounts,
                              List<ChunkDocument> chunks) {
        try {
            JsonNode event = objectMapper.readTree(line);
            String type     = event.path("type").asText();
            String fullName = event.path("repo").path("name").asText();
            if (fullName.isBlank()) return;

            if ("WatchEvent".equals(type)) {
                watchCounts.merge(fullName, 1, Integer::sum);
            }

            if (!trackedRepos.contains(fullName)) return;

            int[] m = metrics.computeIfAbsent(fullName, k -> new int[8]);
            JsonNode payload = event.path("payload");

            switch (type) {
                case "WatchEvent" -> { m[W_WATCH]++; m[W_STAR]++; }
                case "PushEvent"  -> {
                    // commit 수 = commits 배열 크기
                    int commits = payload.path("commits").size();
                    m[W_COMMIT] += Math.max(commits, 1);
                }
                case "PullRequestEvent" -> {
                    String action = payload.path("action").asText();
                    if ("opened".equals(action)) {
                        m[W_PR_OPEN]++;
                    } else if ("closed".equals(action)) {
                        boolean merged = payload.path("pull_request").path("merged").asBoolean();
                        if (merged) m[W_PR_MERGE]++;
                    }
                }
                case "IssuesEvent" -> {
                    String action = payload.path("action").asText();
                    if ("opened".equals(action))      m[W_IS_OPEN]++;
                    else if ("closed".equals(action)) m[W_IS_CLOSE]++;
                }
                case "ReleaseEvent" -> m[W_RELEASE]++;
            }

            chunks.addAll(extractChunks(event, type, fullName, payload));

        } catch (Exception ignored) {
        }
    }

    private List<ChunkDocument> extractChunks(JsonNode event, String type,
                                               String fullName, JsonNode payload) {
        String[] parts = fullName.split("/", 2);
        String owner = parts[0], name = parts[1];
        List<ChunkDocument> result = new ArrayList<>();

        switch (type) {
            case "PushEvent" -> {
                int idx = 0;
                for (JsonNode commit : payload.path("commits")) {
                    String sha     = commit.path("sha").asText();
                    String message = commit.path("message").asText().strip();
                    if (sha.isBlank() || message.isBlank()) continue;
                    result.add(ChunkDocument.builder()
                            .repoOwner(owner).repoName(name)
                            .filePath("events/push")
                            .chunkId(owner + "/" + name + "/commit/" + sha)
                            .chunkIndex(idx++)
                            .heading(sha.length() >= 7 ? sha.substring(0, 7) : sha)
                            .content(message)
                            .url("https://github.com/" + fullName + "/commit/" + sha)
                            .build());
                }
            }
            case "PullRequestEvent" -> {
                JsonNode pr    = payload.path("pull_request");
                String number  = pr.path("number").asText();
                String title   = pr.path("title").asText().strip();
                String body    = pr.path("body").asText().strip();
                String htmlUrl = pr.path("html_url").asText();
                if (title.isBlank()) break;
                result.add(ChunkDocument.builder()
                        .repoOwner(owner).repoName(name)
                        .filePath("events/pull_request")
                        .chunkId(owner + "/" + name + "/pr/" + number)
                        .chunkIndex(0)
                        .heading(title)
                        .content(body.isBlank() ? title : title + "\n\n" + body)
                        .url(htmlUrl)
                        .build());
            }
            case "IssuesEvent" -> {
                JsonNode issue = payload.path("issue");
                String number  = issue.path("number").asText();
                String title   = issue.path("title").asText().strip();
                String body    = issue.path("body").asText().strip();
                String htmlUrl = issue.path("html_url").asText();
                if (title.isBlank()) break;
                result.add(ChunkDocument.builder()
                        .repoOwner(owner).repoName(name)
                        .filePath("events/issue")
                        .chunkId(owner + "/" + name + "/issue/" + number)
                        .chunkIndex(0)
                        .heading(title)
                        .content(body.isBlank() ? title : title + "\n\n" + body)
                        .url(htmlUrl)
                        .build());
            }
        }
        return result;
    }
}
