package gitgalaxy.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GhArchiveProperties;
import gitgalaxy.backend.metrics.CollectorMetrics;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.model.GhArchiveResult;
import gitgalaxy.backend.model.PushCommits;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoMetricsBatchRepository;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.repository.TrendingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final int W_FORK     = 8;
    private static final int METRIC_SIZE = 9;

    private final GhArchiveProperties props;
    private final RepoRepository repoRepository;
    private final RepoHourlyMetricsRepository metricsRepository;
    private final RepoMetricsBatchRepository batchRepository;
    private final ObjectMapper objectMapper;
    private final GithubClient githubClient;
    private final CollectorMetrics collectorMetrics;
    private final TrendingRepository trendingRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 특정 시간의 GH Archive 파일 처리.
     * - 추적 repo 이벤트 집계 → repo_time upsert
     * - commit message(GitHub compare API로 보완) / PR body / issue body → ChunkDocument 반환 (임베딩용)
     * - push·issue 활동 기준을 넘는 미추적 repo → 온보딩 대상으로 반환 (활동량 상위 N개)
     */
    public GhArchiveResult processHour(LocalDateTime bucket) {
        String fileName = bucket.format(HOUR_FMT) + ".json.gz";
        String url = props.getBaseUrl() + "/" + fileName;
        log.info("GH Archive 처리 시작: {}", url);

        Set<String> trackedRepos = new HashSet<>(repoRepository.findTrackedFullNames());

        // fullName → int[9]: watch, commit, prOpen, prMerge, issueOpen, issueClose, star, release, fork
        Map<String, int[]> metrics = new HashMap<>();
        // 추적 repo push 목록 (commit 내용은 payload에 없어 이후 API로 조회)
        List<PushRef> pushes = new ArrayList<>();
        // 발견용 (전체 대상) fullName → int[2]: push, issue
        Map<String, int[]> activity = new HashMap<>();
        // 일간 트렌딩 후보용 (전체 대상) fullName → 스타(WatchEvent) 수
        Map<String, Integer> stars = new HashMap<>();
        List<ChunkDocument> chunks = new ArrayList<>();

        DownloadResult download = collectorMetrics.step("1.GH Archive 다운로드", () -> download(url));
        if (download.file() == null) {
            return GhArchiveResult.empty(download.notFound() ? "not_found" : "failed");
        }
        Path file = download.file();

        long lineCount = 0;
        long parseStart = System.nanoTime();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(file))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                processEvent(line, trackedRepos, metrics, activity, stars, pushes, chunks);
            }
            collectorMetrics.recordStep("2.이벤트 파싱·집계", System.nanoTime() - parseStart);
        } catch (Exception e) {
            log.error("GH Archive 처리 실패 {}: {}", url, e.getMessage(), e);
            return GhArchiveResult.empty("failed");
        } finally {
            deleteQuietly(file);
        }

        log.info("GH Archive {}: {}줄, 이벤트 발생 레포 {}개, chunks {}개", fileName, lineCount, metrics.size(), chunks.size());
        collectorMetrics.ghArchiveEvents(lineCount);

        try {
            trendingRepository.upsertHourlyStars(stars, bucket);
        } catch (Exception e) {
            log.warn("repo_star_hourly 저장 실패: {}", e.getMessage());
        }

        collectorMetrics.step("3.커밋 조회(GitHub GraphQL)", () -> fetchPushCommits(pushes, metrics, chunks));

        // 추적 repo 메트릭 배치 upsert
        try {
            collectorMetrics.step("4.시간별 지표 저장", () -> batchRepository.batchUpsert(metrics, Timestamp.valueOf(bucket)));
        } catch (Exception e) {
            log.warn("repo_time 배치 upsert 실패: {}", e.getMessage());
        }

        // push·issue 활동 기준을 넘는 미추적 repo → 활동량 상위 N개만 온보딩 대상
        List<String> discovered = activity.entrySet().stream()
                .filter(e -> !trackedRepos.contains(e.getKey()))
                .filter(e -> e.getValue()[0] >= props.getMinPushesForDiscovery()
                        && e.getValue()[1] >= props.getMinIssuesForDiscovery())
                .sorted((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]))
                .limit(props.getMaxDiscoveryPerHour())
                .map(Map.Entry::getKey)
                .toList();
        collectorMetrics.reposDiscovered(discovered.size());
        if (!discovered.isEmpty()) {
            log.info("GH Archive: 신규 repo 후보 {}개 (push≥{}, issue≥{}, 상한 {})", discovered.size(),
                    props.getMinPushesForDiscovery(), props.getMinIssuesForDiscovery(), props.getMaxDiscoveryPerHour());
        }

        return new GhArchiveResult(chunks, metrics.keySet(), discovered, "success");
    }

    // ─── private ──────────────────────────────────────────

    private void processEvent(String line, Set<String> trackedRepos,
                              Map<String, int[]> metrics, Map<String, int[]> activity,
                              Map<String, Integer> stars, List<PushRef> pushes, List<ChunkDocument> chunks) {
        try {
            JsonNode event = objectMapper.readTree(line);
            String type     = event.path("type").asText();
            String fullName = event.path("repo").path("name").asText();
            if (fullName.isBlank()) return;

            if ("PushEvent".equals(type)) {
                activity.computeIfAbsent(fullName, k -> new int[2])[0]++;
            } else if ("IssuesEvent".equals(type)) {
                activity.computeIfAbsent(fullName, k -> new int[2])[1]++;
            } else if ("WatchEvent".equals(type)) {
                stars.merge(fullName, 1, Integer::sum);
            }

            if (!trackedRepos.contains(fullName)) return;

            int[] m = metrics.computeIfAbsent(fullName, k -> new int[METRIC_SIZE]);
            JsonNode payload = event.path("payload");

            switch (type) {
                case "WatchEvent" -> { m[W_WATCH]++; m[W_STAR]++; }
                case "PushEvent"  -> {
                    // payload에 commits 없음 → 일단 push당 1, compare 조회 성공 시 실제 커밋 수로 보정
                    m[W_COMMIT]++;
                    pushes.add(new PushRef(fullName, payload.path("before").asText(), payload.path("head").asText()));
                }
                case "PullRequestEvent" -> {
                    String action = payload.path("action").asText();
                    if ("opened".equals(action)) {
                        m[W_PR_OPEN]++;
                    } else if ("closed".equals(action)) {
                        // merge는 별도 action 없이 closed + merged=true 로 옴
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
                case "ForkEvent"    -> m[W_FORK]++;
            }

            chunks.addAll(extractChunks(event, type, fullName, payload));

        } catch (Exception ignored) {
        }
    }

    /**
     * GH Archive 파일을 임시 파일로 끝까지 받은 뒤 반환 (스트리밍 중 연결 끊김 방지).
     * 요청 타임아웃 + 재시도. 404(아직 미업로드)는 재시도하지 않음. 최종 실패 시 null.
     */
    /** 하루 활동량 배열 인덱스 */
    public static final int A_PUSH = 0, A_PR_OPEN = 1, A_PR_MERGE = 2, A_IS_OPEN = 3, A_IS_CLOSE = 4, A_RELEASE = 5, A_FORK = 6;

    public record DayActivity(Map<String, int[]> counts, int hours) {}

    /**
     * 하루(UTC 00~24시) GH Archive 24개 파일에서 지정 repo들의 활동량 집계 (일간 트렌딩 별 밝기용).
     * 아직 올라오지 않았거나 실패한 시간대는 건너뛰고, 집계한 시간대 수를 함께 반환.
     */
    public DayActivity countDayActivity(java.time.LocalDate day, Set<String> repos) {
        Map<String, int[]> counts = new HashMap<>();
        int hours = 0;
        for (int h = 0; h < 24; h++) {
            String url = props.getBaseUrl() + "/" + day.atTime(h, 0).format(HOUR_FMT) + ".json.gz";
            DownloadResult download = download(url);
            if (download.file() == null) continue;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(Files.newInputStream(download.file()))))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    countActivity(line, repos, counts);
                }
                hours++;
            } catch (Exception e) {
                log.warn("일간 활동량 집계 실패 {}: {}", url, e.getMessage());
            } finally {
                deleteQuietly(download.file());
            }
        }
        log.info("일간 활동량 {}: 대상 {}개, 활동 있는 repo {}개, GH Archive {}/24시간", day, repos.size(), counts.size(), hours);
        return new DayActivity(counts, hours);
    }

    private void countActivity(String line, Set<String> repos, Map<String, int[]> counts) {
        try {
            JsonNode event = objectMapper.readTree(line);
            String fullName = event.path("repo").path("name").asText();
            if (!repos.contains(fullName)) return;
            JsonNode payload = event.path("payload");
            String action = payload.path("action").asText();
            int idx = switch (event.path("type").asText()) {
                case "PushEvent" -> A_PUSH;
                case "PullRequestEvent" -> "opened".equals(action) ? A_PR_OPEN
                        : ("closed".equals(action) && payload.path("pull_request").path("merged").asBoolean()) ? A_PR_MERGE : -1;
                case "IssuesEvent" -> "opened".equals(action) ? A_IS_OPEN : "closed".equals(action) ? A_IS_CLOSE : -1;
                case "ReleaseEvent" -> A_RELEASE;
                case "ForkEvent" -> A_FORK;
                default -> -1;
            };
            if (idx >= 0) counts.computeIfAbsent(fullName, k -> new int[7])[idx]++;
        } catch (Exception ignored) {
        }
    }

    private record DownloadResult(Path file, boolean notFound) {}

    private DownloadResult download(String url) {
        int maxAttempts = props.getDownloadMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Path tmp = null;
            long start = System.nanoTime();
            try {
                tmp = Files.createTempFile("gharchive-", ".json.gz");
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "GitGalaxy/1.0")
                        .timeout(Duration.ofSeconds(props.getDownloadTimeoutSeconds()))
                        .build();
                // send()가 timeout을 무시하고 멈춘 사례가 있어 바깥에서도 시간 제한 (요청 타임아웃 + 30초)
                var future = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofFile(tmp));
                HttpResponse<Path> resp;
                try {
                    resp = future.get(props.getDownloadTimeoutSeconds() + 30L, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    future.cancel(true);
                    throw te;
                }
                if (resp.statusCode() == 200) {
                    collectorMetrics.ghArchiveDownload("success", start);
                    return new DownloadResult(tmp, false);
                }

                deleteQuietly(tmp);
                if (resp.statusCode() == 404) {
                    collectorMetrics.ghArchiveDownload("not_found", start);
                    log.warn("GH Archive 파일 없음 (HTTP 404): {}", url);
                    return new DownloadResult(null, true);
                }
                collectorMetrics.ghArchiveDownload("error", start);
                log.warn("GH Archive 다운로드 실패 (HTTP {}) {}/{}: {}", resp.statusCode(), attempt, maxAttempts, url);
            } catch (InterruptedException e) {
                deleteQuietly(tmp);
                Thread.currentThread().interrupt();
                return new DownloadResult(null, false);
            } catch (Exception e) {
                deleteQuietly(tmp);
                collectorMetrics.ghArchiveDownload("error", start);
                log.warn("GH Archive 다운로드 실패 {}/{}: {} ({})", attempt, maxAttempts, url, e.toString());
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(5_000L * attempt); // 5s → 10s → ...
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new DownloadResult(null, false);
                }
            }
        }
        log.error("GH Archive 다운로드 최종 실패 ({}회 시도): {}", maxAttempts, url);
        return new DownloadResult(null, false);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private record PushRef(String fullName, String before, String head) {}

    /**
     * 추적 repo push의 커밋을 GitHub GraphQL 묶음 조회로 가져옴.
     * - 커밋 메시지 → ChunkDocument
     * - repo_time commit_count를 push 수 → 실제 커밋 수로 보정
     * - repo당 / 시간당 조회 상한 (봇 repo 독점 방지)
     */
    private void fetchPushCommits(List<PushRef> pushes, Map<String, int[]> metrics, List<ChunkDocument> chunks) {
        // 1) 조회할 push 선정 (순서대로): 시간당·레포당 상한 → 호출 수와 한도 규칙은 순차 처리 때와 동일
        Map<String, Integer> perRepo = new HashMap<>();
        List<PushRef> selected = new ArrayList<>();
        for (PushRef push : pushes) {
            if (push.head().isBlank()) continue;
            if (selected.size() >= props.getMaxCommitFetchPerHour()
                    || perRepo.merge(push.fullName(), 1, Integer::sum) > props.getMaxCommitFetchPerRepo()) {
                collectorMetrics.commitFetch("skipped_limit");
                continue;
            }
            selected.add(push);
        }

        // 2) GraphQL 묶음(기본 25건) 단위로 조회, 묶음끼리는 동시에. REST와 한도가 따로라 REST 여유분과 무관
        int concurrency = Math.max(1, props.getCommitFetchConcurrency());
        int batchSize = Math.max(1, props.getCommitFetchBatchSize());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        List<java.util.concurrent.Future<List<PushCommits>>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger cost = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(-1);
        try {
            for (int i = 0; i < selected.size(); i += batchSize) {
                List<PushRef> batch = selected.subList(i, Math.min(i + batchSize, selected.size()));
                futures.add(pool.submit(() -> fetchBatchSplitting(batch, cost, requests, remaining)));
            }

            // 3) 결과 반영은 한 스레드에서 (metrics·chunks는 스레드 안전하지 않음)
            int fetched = 0, failed = 0;
            List<PushRef> needRest = new ArrayList<>();
            for (int b = 0; b < futures.size(); b++) {
                List<PushCommits> results;
                try {
                    results = futures.get(b).get();
                } catch (Exception e) {
                    results = java.util.Collections.nCopies(Math.min(batchSize, selected.size() - b * batchSize), null);
                    log.warn("커밋 조회 묶음 실패: {}", e.getMessage());
                }
                for (int j = 0; j < results.size(); j++) {
                    PushRef push = selected.get(b * batchSize + j);
                    PushCommits result = results.get(j);
                    if (result == null) {
                        failed++;
                        collectorMetrics.commitFetch("failed");
                        continue;
                    }
                    if (result.totalCommits() < 0) { // 이력에서 before를 못 찾음 → REST compare로 정확히
                        needRest.add(push);
                        continue;
                    }
                    fetched++;
                    collectorMetrics.commitFetch("success");
                    applyCommits(push, result, metrics, chunks);
                }
            }

            // 4) 보완 조회: REST compare (소수라 동시 요청 + REST 여유분 확인)
            List<java.util.concurrent.Future<PushCommits>> restFutures = new ArrayList<>();
            for (PushRef push : needRest) {
                restFutures.add(pool.submit(() -> {
                    if (!githubClient.hasBudget()) return null;
                    String[] parts = push.fullName().split("/", 2);
                    return githubClient.getPushCommits(parts[0], parts[1], push.before(), push.head());
                }));
            }
            for (int k = 0; k < needRest.size(); k++) {
                PushCommits result;
                try {
                    result = restFutures.get(k).get();
                } catch (Exception e) {
                    result = null;
                }
                if (result == null) {
                    failed++;
                    collectorMetrics.commitFetch("failed");
                    continue;
                }
                fetched++;
                collectorMetrics.commitFetch("success");
                applyCommits(needRest.get(k), result, metrics, chunks);
            }
            log.info("GH Archive: push {}건 중 {}건 커밋 조회 (실패 {}건, GraphQL {}회·{}포인트 + REST 보완 {}회, 동시 {}개, 남은 GraphQL 포인트 {})",
                    pushes.size(), fetched, failed, requests.get(), cost.get(), needRest.size(), concurrency, remaining.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 묶음 조회. 요청 자체가 실패하면(502 등) 절반씩 나눠 재시도 → 문제 push 하나 때문에 묶음 전체를 잃지 않음 */
    private List<PushCommits> fetchBatchSplitting(List<PushRef> batch, java.util.concurrent.atomic.AtomicInteger cost,
                                                  java.util.concurrent.atomic.AtomicInteger requests,
                                                  java.util.concurrent.atomic.AtomicInteger remaining) {
        try {
            requests.incrementAndGet();
            GithubClient.PushCommitsBatch r = githubClient.getPushCommitsBatch(batch.stream().map(p -> {
                String[] parts = p.fullName().split("/", 2);
                return new GithubClient.PushQuery(parts[0], parts[1], p.before(), p.head());
            }).toList(), props.getCommitHistoryDepth());
            cost.addAndGet(r.cost());
            if (r.remaining() >= 0) remaining.set(r.remaining());
            return r.results();
        } catch (Exception e) {
            if (batch.size() == 1) {
                log.debug("커밋 조회 실패 {}: {}", batch.get(0).fullName(), e.getMessage());
                return java.util.Collections.singletonList(null);
            }
            int mid = batch.size() / 2;
            log.info("커밋 조회 묶음({}건) 실패 → {}건 + {}건으로 나눠 재시도: {}", batch.size(), mid, batch.size() - mid, e.getMessage());
            List<PushCommits> merged = new ArrayList<>(fetchBatchSplitting(batch.subList(0, mid), cost, requests, remaining));
            merged.addAll(fetchBatchSplitting(batch.subList(mid, batch.size()), cost, requests, remaining));
            return merged;
        }
    }

    /** 커밋 메시지 → ChunkDocument, push당 1로 세어둔 커밋 수를 실제 커밋 수로 보정 */
    private void applyCommits(PushRef push, PushCommits result, Map<String, int[]> metrics, List<ChunkDocument> chunks) {
        metrics.get(push.fullName())[W_COMMIT] += result.totalCommits() - 1;
        String[] parts = push.fullName().split("/", 2);
        int idx = 0;
        for (PushCommits.Commit commit : result.commits()) {
            String message = commit.message().strip();
            if (commit.sha().isBlank() || message.isBlank()) continue;
            chunks.add(ChunkDocument.builder()
                    .repoOwner(parts[0]).repoName(parts[1])
                    .filePath("events/push")
                    .chunkId(push.fullName() + "/commit/" + commit.sha())
                    .chunkIndex(idx++)
                    .heading(commit.sha().length() >= 7 ? commit.sha().substring(0, 7) : commit.sha())
                    .content(message)
                    .url("https://github.com/" + push.fullName() + "/commit/" + commit.sha())
                    .build());
        }
    }

    private List<ChunkDocument> extractChunks(JsonNode event, String type,
                                               String fullName, JsonNode payload) {
        String[] parts = fullName.split("/", 2);
        String owner = parts[0], name = parts[1];
        List<ChunkDocument> result = new ArrayList<>();

        switch (type) {
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
