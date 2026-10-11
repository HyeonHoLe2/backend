package gitgalaxy.backend;

import gitgalaxy.backend.service.EmbeddingService;
import gitgalaxy.backend.service.LlmService;
import gitgalaxy.backend.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * 커밋 조회 방식에 따른 RAG(/explain) 정확도 비교: GraphQL만 vs GraphQL + REST 보완(하이브리드).
 * 한 시간대에서 REST 보완으로만 얻은 커밋(F)과 GraphQL로 얻은 커밋(C)에 대해 질문을 만들고,
 * F 청크를 검색에서 뺀 상태(GraphQL만)와 그대로(하이브리드)에서 Hit@5·답변 정답률(LLM 채점)을 비교.
 * 입력: RAG_EVAL_SPLIT=split.json (f/c: chunk_id, repo, content)
 * 실행: RUN_BENCH=true RAG_EVAL_SPLIT=... ./gradlew test --tests CommitFallbackRagEvalTest
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "true")
class CommitFallbackRagEvalTest {

    private static final int PER_GROUP = 40;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private LlmService llm;
    @Autowired private RagService ragService;
    @Autowired private ObjectMapper objectMapper;

    record Item(String group, String chunkId, String repo, String content) {}
    record Result(Item item, String question, boolean hitGql, boolean hitHybrid, boolean okGql, boolean okHybrid) {}

    @Test
    void compare() throws Exception {
        JsonNode split = objectMapper.readTree(new File(System.getenv("RAG_EVAL_SPLIT")));
        List<String> fIds = new ArrayList<>();
        split.path("f").forEach(n -> fIds.add(n.path("chunk_id").asText()));
        String[] excluded = fIds.toArray(String[]::new);

        List<Item> items = new ArrayList<>();
        items.addAll(sample("F", split.path("f")));
        items.addAll(sample("C", split.path("c")));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Result>> futures = new ArrayList<>();
        for (Item it : items) futures.add(pool.submit(() -> evaluate(it, excluded)));
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            try { results.add(f.get()); } catch (Exception e) { System.out.println("평가 실패: " + e.getMessage()); }
        }
        pool.shutdown();

        for (String g : List.of("F", "C")) {
            List<Result> rs = results.stream().filter(r -> r.item().group().equals(g)).toList();
            System.out.printf("%n[%s] %s %d문항%n", g, g.equals("F") ? "REST 보완으로만 얻은 커밋" : "GraphQL로 얻은 커밋", rs.size());
            System.out.printf("  Hit@5     GraphQL만 %d/%d  하이브리드 %d/%d%n",
                    rs.stream().filter(Result::hitGql).count(), rs.size(), rs.stream().filter(Result::hitHybrid).count(), rs.size());
            System.out.printf("  답변 정답 GraphQL만 %d/%d  하이브리드 %d/%d%n",
                    rs.stream().filter(Result::okGql).count(), rs.size(), rs.stream().filter(Result::okHybrid).count(), rs.size());
        }
        results.stream().filter(r -> r.item().group().equals("F")).limit(3).forEach(r ->
                System.out.printf("%n예시 [%s] 질문: %s%n  커밋: %s%n  GraphQL만 정답=%s / 하이브리드 정답=%s%n",
                        r.item().repo(), r.question(), r.item().content().replace("\n", " ").substring(0, Math.min(120, r.item().content().length())),
                        r.okGql(), r.okHybrid()));
    }

    /** DB에 있는 청크 중 정보가 있는 메시지(30자 이상, 머지 자동 메시지 제외)만, 레포가 겹치지 않게 무작위 추출 */
    private List<Item> sample(String group, JsonNode arr) {
        List<Item> all = new ArrayList<>();
        arr.forEach(n -> all.add(new Item(group, n.path("chunk_id").asText(), n.path("repo").asText(), n.path("content").asText())));
        Collections.shuffle(all, new Random(42));
        Set<String> repos = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            String c = it.content().strip();
            if (c.length() < 30 || c.startsWith("Merge ")) continue;
            if (!repos.add(it.repo())) continue;
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM chunk_embeddings WHERE chunk_id = ?", Integer.class, it.chunkId());
            if (exists == null || exists == 0) continue;
            out.add(it);
            if (out.size() == PER_GROUP) break;
        }
        return out;
    }

    private Result evaluate(Item it, String[] excluded) throws Exception {
        String question = llm.chat("""
                다음은 GitHub 레포 %s의 커밋 메시지입니다.
                이 변경 내용에 대해 사용자가 물어볼 만한 자연스러운 한국어 질문 1개만 출력하세요.
                커밋 메시지 문장을 그대로 옮기지 말고, 해시·파일명은 쓰지 마세요.

                커밋 메시지:
                %s""".formatted(it.repo(), it.content()));
        if (question == null) throw new IllegalStateException("질문 생성 빈 응답");
        question = question.strip();
        String vec = EmbeddingService.toVectorString(embeddingService.embed(question));
        String[] p = it.repo().split("/", 2);

        List<Map<String, Object>> hybrid = search(vec, p[0], p[1], null);
        List<Map<String, Object>> gqlOnly = search(vec, p[0], p[1], excluded);
        boolean hitHybrid = hybrid.stream().anyMatch(c -> it.chunkId().equals(c.get("chunk_id")));
        boolean hitGql = gqlOnly.stream().anyMatch(c -> it.chunkId().equals(c.get("chunk_id")));

        boolean okHybrid = judge(it, question, answer(it.repo(), question, hybrid));
        boolean okGql = judge(it, question, answer(it.repo(), question, gqlOnly));
        return new Result(it, question, hitGql, hitHybrid, okGql, okHybrid);
    }

    /** /explain과 같은 레포 범위 top-5 검색. excluded가 있으면 해당 청크를 뺀 상태(GraphQL만)로 검색 */
    private List<Map<String, Object>> search(String vec, String owner, String repo, String[] excluded) {
        String sql = """
                SELECT chunk_id, repo_owner, repo_name, file_path, heading, content
                FROM chunk_embeddings
                WHERE repo_owner = ? AND repo_name = ? %s
                ORDER BY embedding <=> ?::vector LIMIT 5""".formatted(excluded == null ? "" : "AND chunk_id <> ALL(?)");
        return jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            int i = 1;
            ps.setString(i++, owner);
            ps.setString(i++, repo);
            if (excluded != null) ps.setArray(i++, con.createArrayOf("text", excluded));
            ps.setString(i, vec);
            return ps;
        }, (rs, n) -> {
            Map<String, Object> m = new HashMap<>();
            for (String col : List.of("chunk_id", "repo_owner", "repo_name", "file_path", "heading", "content")) m.put(col, rs.getString(col));
            return m;
        });
    }

    /** RagService.explain과 같은 컨텍스트·프롬프트로 답변 생성 */
    private String answer(String repo, String question, List<Map<String, Object>> chunks) throws Exception {
        if (chunks.isEmpty()) return "해당 repo의 문서 데이터가 없습니다.";
        Method ctx = RagService.class.getDeclaredMethod("buildContext", List.class);
        Method prompt = RagService.class.getDeclaredMethod("buildPrompt", String.class, String.class, String.class);
        ctx.setAccessible(true);
        prompt.setAccessible(true);
        return Objects.toString(llm.chat((String) prompt.invoke(ragService, repo, question, ctx.invoke(ragService, chunks))), "");
    }

    private boolean judge(Item it, String question, String answer) {
        String v = Objects.toString(llm.chat("""
                [실제 커밋 메시지]
                %s

                [질문]
                %s

                [답변]
                %s

                답변이 실제 커밋 메시지의 핵심 변경 내용을 맞게 설명하면 YES,
                모른다고 하거나 틀리거나 다른 내용이면 NO. YES 또는 NO 한 단어만 출력하세요.""".formatted(it.content(), question, answer)), "");
        return v.strip().toUpperCase().startsWith("YES");
    }
}
