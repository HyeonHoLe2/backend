package gitgalaxy.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GhArchiveProperties;
import gitgalaxy.backend.entity.Repo;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class GhArchiveService {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-H");

    private final GhArchiveProperties props;
    private final RepoRepository repoRepository;
    private final RepoHourlyMetricsRepository metricsRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 특정 시간(hourBucket)의 GH Archive 파일을 처리.
     * - 추적 중인 repo의 이벤트 → repo_hourly_metrics upsert
     * - WatchEvent ≥ minWatch인 신규 repo → repos에 tracked=false로 등록
     */
    public void processHour(LocalDateTime hourBucket) {
        String fileName = hourBucket.format(HOUR_FMT) + ".json.gz";
        String url = props.getBaseUrl() + "/" + fileName;
        log.info("GH Archive 처리 시작: {}", url);

        Set<String> trackedRepos = repoRepository.findByTrackedTrue()
                .stream()
                .map(r -> r.getOwner() + "/" + r.getName())
                .collect(Collectors.toSet());

        // fullName → [watch, push, pr, issue]
        Map<String, int[]> metrics = new HashMap<>();
        // fullName → watchCount (발견용, 전체 대상)
        Map<String, Integer> watchCounts = new HashMap<>();

        long lineCount = 0;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "GitGalaxy/1.0")
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.warn("GH Archive 파일 없음 (HTTP {}): {}", resp.statusCode(), url);
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(resp.body())))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    processEvent(line, trackedRepos, metrics, watchCounts);
                }
            }
        } catch (Exception e) {
            log.error("GH Archive 처리 실패 {}: {}", url, e.getMessage(), e);
            return;
        }

        log.info("GH Archive {}: {}줄 처리, tracked {}개 메트릭 업데이트", fileName, lineCount, metrics.size());

        for (Map.Entry<String, int[]> entry : metrics.entrySet()) {
            String[] parts = entry.getKey().split("/", 2);
            int[] m = entry.getValue();
            metricsRepository.upsert(parts[0], parts[1], hourBucket, m[0], m[1], m[2], m[3]);
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
                                    .fullName(fullName)
                                    .owner(parts[0])
                                    .name(parts[1])
                                    .tracked(false)
                                    .createdAt(LocalDateTime.now())
                                    .build())
                    );
                    discovered++;
                } catch (Exception e) {
                    log.warn("신규 repo 등록 실패 {}: {}", fullName, e.getMessage());
                }
            }
        }
        if (discovered > 0) {
            log.info("GH Archive: 신규 repo 후보 {}개 발견 (watch≥{})", discovered, props.getMinWatchCountForDiscovery());
        }
    }

    private void processEvent(String line, Set<String> trackedRepos,
                              Map<String, int[]> metrics, Map<String, Integer> watchCounts) {
        try {
            JsonNode event = objectMapper.readTree(line);
            String type = event.path("type").asText();
            String fullName = event.path("repo").path("name").asText();
            if (fullName.isBlank()) return;

            if ("WatchEvent".equals(type)) {
                watchCounts.merge(fullName, 1, Integer::sum);
            }

            if (!trackedRepos.contains(fullName)) return;

            int[] m = metrics.computeIfAbsent(fullName, k -> new int[4]);
            switch (type) {
                case "WatchEvent"       -> m[0]++;
                case "PushEvent"        -> m[1]++;
                case "PullRequestEvent" -> m[2]++;
                case "IssuesEvent"      -> m[3]++;
            }
        } catch (Exception ignored) {
        }
    }
}
