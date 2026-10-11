package gitgalaxy.backend;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.RepoMeta;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.service.GithubClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 메타 갱신 방식 비교: REST(repo당 1회, 현재 RepoMetaRefreshJob) vs GraphQL(100개당 1회).
 * 갱신 대기열 앞 1,000개를 두 방식으로 조회만 하고(DB 저장 없음) 시간·호출 수·결과 일치를 비교.
 * 실행: RUN_BENCH=true ./gradlew test --tests MetaRefreshBenchmarkTest
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "true")
class MetaRefreshBenchmarkTest {

    private static final int REPOS = 1000;
    private static final int GQL_BATCH = 100;

    @Autowired private RepoRepository repoRepository;
    @Autowired private GithubClient githubClient;
    @Autowired private ObjectMapper objectMapper;

    record Meta(String name, RepoMeta meta) {}

    @Test
    void compare() throws Exception {
        List<String> names = repoRepository.findStalestTracked(PageRequest.of(0, REPOS)).stream().map(Repo::getFullName).toList();

        // REST: 현재 잡과 동일 (repo당 1회, 이동한 repo는 1회 더)
        Map<String, Meta> rest = new HashMap<>();
        int restFail = 0;
        long t0 = System.nanoTime();
        for (String n : names) {
            String[] p = n.split("/", 2);
            try {
                GithubClient.RepoMetaWithName r = githubClient.getRepoMetaFollowingMoves(p[0], p[1]);
                rest.put(n, new Meta(r.fullName(), r.meta()));
            } catch (Exception e) {
                restFail++;
            }
        }
        long restMs = (System.nanoTime() - t0) / 1_000_000;

        // GraphQL: 100개 묶음, 이름 변경은 nameWithOwner로 확인
        Map<String, Meta> gql = new HashMap<>();
        int gqlRequests = 0, gqlCost = 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < names.size(); i += GQL_BATCH) {
            List<String> batch = names.subList(i, Math.min(i + GQL_BATCH, names.size()));
            StringBuilder q = new StringBuilder("query { rateLimit { cost }");
            for (int j = 0; j < batch.size(); j++) {
                String[] p = batch.get(j).split("/", 2);
                q.append(" r").append(j).append(": repository(owner: ").append(objectMapper.writeValueAsString(p[0]))
                 .append(", name: ").append(objectMapper.writeValueAsString(p[1]))
                 .append(") { nameWithOwner description stargazerCount forkCount issues(states: OPEN) { totalCount }")
                 .append(" pullRequests(states: OPEN) { totalCount } primaryLanguage { name } defaultBranchRef { name } }");
            }
            JsonNode data = githubClient.graphql(q.append(" }").toString());
            gqlRequests++;
            gqlCost += data.path("rateLimit").path("cost").asInt();
            for (int j = 0; j < batch.size(); j++) {
                JsonNode r = data.path("r" + j);
                if (r.isMissingNode() || r.isNull()) continue;
                gql.put(batch.get(j), new Meta(r.path("nameWithOwner").asText(), new RepoMeta(
                        r.path("defaultBranchRef").path("name").asText("main"),
                        r.path("description").isNull() ? "" : r.path("description").asText(""),
                        r.path("stargazerCount").asInt(), r.path("forkCount").asInt(),
                        // REST open_issues_count = 열린 이슈 + 열린 PR
                        r.path("issues").path("totalCount").asInt() + r.path("pullRequests").path("totalCount").asInt(),
                        r.path("primaryLanguage").path("name").asText(null))));
            }
        }
        long gqlMs = (System.nanoTime() - t1) / 1_000_000;

        int same = 0, starDiff = 0, otherDiff = 0, onlyRest = 0, onlyGql = 0, moved = 0, movedSame = 0;
        for (String n : names) {
            Meta a = rest.get(n), b = gql.get(n);
            if (a == null && b == null) continue;
            if (a == null) { onlyGql++; continue; }
            if (b == null) { onlyRest++; continue; }
            if (!a.name().equalsIgnoreCase(n)) { moved++; if (a.name().equalsIgnoreCase(b.name())) movedSame++; }
            RepoMeta x = a.meta(), y = b.meta();
            boolean others = Objects.equals(x.description(), y.description()) && Objects.equals(x.language(), y.language())
                    && Objects.equals(x.defaultBranch(), y.defaultBranch()) && x.forksCount() == y.forksCount()
                    && x.openIssuesCount() == y.openIssuesCount();
            if (!others) otherDiff++;
            else if (x.stargazersCount() != y.stargazersCount()) starDiff++;
            else same++;
        }
        System.out.printf("%n[REST]    %d개 조회: %,d ms, 성공 %d / 실패 %d (REST 호출 약 %d회)%n", names.size(), restMs, rest.size(), restFail, names.size() + moved);
        System.out.printf("[GraphQL] %d개 조회: %,d ms, 성공 %d (GraphQL %d회, %d포인트)%n", names.size(), gqlMs, gql.size(), gqlRequests, gqlCost);
        System.out.printf("[일치] 완전 일치 %d / 스타 수만 다름(조회 사이 변동) %d / 그 외 필드 다름 %d / REST만 성공 %d / GraphQL만 성공 %d / 이동 repo %d 중 새 이름 일치 %d%n",
                same, starDiff, otherDiff, onlyRest, onlyGql, moved, movedSame);
    }
}
