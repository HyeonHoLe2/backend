package gitgalaxy.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub GraphQL로 repo 누적 스타 수(stargazerCount) 일괄 조회.
 * 요청 1번에 100개씩 묶음 → REST 대비 호출 수 1/100. (GraphQL 한도는 REST와 별도)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StarStatsService {

    private static final int BATCH_SIZE = 100;

    private final GithubClient githubClient;
    private final gitgalaxy.backend.config.GithubCollectorProperties collectorProps;

    /** 삭제·비공개·이름 변경된 repo는 결과에서 빠짐 */
    public Map<String, Integer> fetchStargazerCounts(List<String> fullNames) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < fullNames.size(); i += BATCH_SIZE) {
            List<String> batch = fullNames.subList(i, Math.min(i + BATCH_SIZE, fullNames.size()));
            try {
                StringBuilder q = new StringBuilder("query {");
                for (int j = 0; j < batch.size(); j++) {
                    String[] p = batch.get(j).split("/", 2);
                    if (p.length != 2) continue;
                    q.append(" r").append(j).append(": repository(owner: ").append(quote(p[0]))
                     .append(", name: ").append(quote(p[1])).append(") { stargazerCount }");
                }
                JsonNode data = githubClient.graphql(q.append(" }").toString());
                for (int j = 0; j < batch.size(); j++) {
                    JsonNode repo = data.path("r" + j);
                    if (!repo.isMissingNode() && !repo.isNull()) {
                        result.put(batch.get(j), repo.path("stargazerCount").asInt());
                    }
                }
            } catch (Exception e) {
                log.warn("스타 수 일괄 조회 실패 ({}개): {}", batch.size(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 배경 repo 가벼운 등록용 메타 일괄 조회 (REST repo당 1회 → GraphQL 100개당 1회).
     * 삭제·비공개·이름 변경된 repo는 결과에서 빠짐.
     */
    public Map<String, gitgalaxy.backend.model.RepoMeta> fetchRepoMeta(List<String> fullNames) {
        Map<String, gitgalaxy.backend.model.RepoMeta> result = new HashMap<>();
        for (int i = 0; i < fullNames.size(); i += BATCH_SIZE) {
            List<String> batch = fullNames.subList(i, Math.min(i + BATCH_SIZE, fullNames.size()));
            try {
                StringBuilder q = new StringBuilder("query {");
                for (int j = 0; j < batch.size(); j++) {
                    String[] p = batch.get(j).split("/", 2);
                    if (p.length != 2) continue;
                    q.append(" r").append(j).append(": repository(owner: ").append(quote(p[0]))
                     .append(", name: ").append(quote(p[1])).append(") { description stargazerCount forkCount")
                     .append(" issues(states: OPEN) { totalCount } primaryLanguage { name } defaultBranchRef { name } }");
                }
                JsonNode data = githubClient.graphql(q.append(" }").toString());
                for (int j = 0; j < batch.size(); j++) {
                    JsonNode r = data.path("r" + j);
                    if (r.isMissingNode() || r.isNull()) continue;
                    result.put(batch.get(j), new gitgalaxy.backend.model.RepoMeta(
                            r.path("defaultBranchRef").path("name").asText("main"),
                            r.path("description").isNull() ? "" : r.path("description").asText(""),
                            r.path("stargazerCount").asInt(), r.path("forkCount").asInt(),
                            r.path("issues").path("totalCount").asInt(),
                            r.path("primaryLanguage").path("name").asText(null)));
                }
            } catch (Exception e) {
                log.warn("메타 일괄 조회 실패 ({}개): {}", batch.size(), e.getMessage());
            }
        }
        return result;
    }

    /** README 본문까지 받으면 요청이 무거워 50개 묶음에서 GitHub 502(시간 초과)가 났음 → 25개 */
    private static final int PROFILE_BATCH_SIZE = 25;

    /**
     * 레포 프로필 일괄 조회: 메타 + 토픽 + README 원문 (README.md / readme.md).
     * REST 메타 + raw 다운로드를 레포마다 하던 것을 GraphQL 25개당 1회로.
     * 묶음이 실패하면 절반씩 나눠 재시도 → 문제 있는 repo 하나 때문에 묶음 전체를 잃지 않음.
     */
    public Map<String, gitgalaxy.backend.model.RepoProfile> fetchProfiles(List<String> fullNames) {
        // 묶음끼리는 독립적이라 동시에 요청 (GitHub 응답 대기 시간이 대부분)
        Map<String, gitgalaxy.backend.model.RepoProfile> result = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, collectorProps.getGraphqlConcurrency()));
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < fullNames.size(); i += PROFILE_BATCH_SIZE) {
                List<String> batch = fullNames.subList(i, Math.min(i + PROFILE_BATCH_SIZE, fullNames.size()));
                futures.add(pool.submit(() -> fetchProfilesSplitting(batch, result)));
            }
            for (java.util.concurrent.Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("프로필 조회 작업 오류: {}", e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private void fetchProfilesSplitting(List<String> batch, Map<String, gitgalaxy.backend.model.RepoProfile> out) {
        try {
            fetchProfileBatch(batch, out);
        } catch (Exception e) {
            if (batch.size() == 1) {
                log.warn("프로필 조회 실패 {}: {}", batch.get(0), e.getMessage());
                return;
            }
            int mid = batch.size() / 2;
            log.info("프로필 묶음({}개) 실패 → {}개 + {}개로 나눠 재시도: {}", batch.size(), mid, batch.size() - mid, e.getMessage());
            fetchProfilesSplitting(batch.subList(0, mid), out);
            fetchProfilesSplitting(batch.subList(mid, batch.size()), out);
        }
    }

    private void fetchProfileBatch(List<String> batch, Map<String, gitgalaxy.backend.model.RepoProfile> out) {
        StringBuilder q = new StringBuilder("query {");
        for (int j = 0; j < batch.size(); j++) {
            String[] p = batch.get(j).split("/", 2);
            if (p.length != 2) continue;
            q.append(" r").append(j).append(": repository(owner: ").append(quote(p[0]))
             .append(", name: ").append(quote(p[1])).append(") { description stargazerCount forkCount")
             .append(" issues(states: OPEN) { totalCount } primaryLanguage { name } defaultBranchRef { name }")
             .append(" repositoryTopics(first: 20) { nodes { topic { name } } }")
             .append(" readme1: object(expression: \"HEAD:README.md\") { ... on Blob { text } }")
             .append(" readme2: object(expression: \"HEAD:readme.md\") { ... on Blob { text } } }");
        }
        JsonNode data = githubClient.graphql(q.append(" }").toString());
        for (int j = 0; j < batch.size(); j++) {
            JsonNode r = data.path("r" + j);
            if (r.isMissingNode() || r.isNull()) continue;
            gitgalaxy.backend.model.RepoMeta meta = new gitgalaxy.backend.model.RepoMeta(
                    r.path("defaultBranchRef").path("name").asText("main"),
                    r.path("description").isNull() ? "" : r.path("description").asText(""),
                    r.path("stargazerCount").asInt(), r.path("forkCount").asInt(),
                    r.path("issues").path("totalCount").asInt(),
                    r.path("primaryLanguage").path("name").asText(null));
            List<String> topics = new java.util.ArrayList<>();
            for (JsonNode t : r.path("repositoryTopics").path("nodes")) topics.add(t.path("topic").path("name").asText());
            String readme = r.path("readme1").path("text").asText(null);
            if (readme == null) readme = r.path("readme2").path("text").asText(null);
            out.put(batch.get(j), new gitgalaxy.backend.model.RepoProfile(meta, topics, readme));
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
