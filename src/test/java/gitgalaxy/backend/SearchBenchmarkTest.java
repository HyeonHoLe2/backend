package gitgalaxy.backend;

import gitgalaxy.backend.document.RepoDocument;
import gitgalaxy.backend.repository.RepoElasticsearchRepository;
import gitgalaxy.backend.repository.RepoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Random;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles({"dev", "test"})
class SearchBenchmarkTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RepoRepository repoRepository;
    @Autowired private RepoElasticsearchRepository esRepository;
    @Autowired private ElasticsearchOperations elasticsearchOperations;

    private static final int    TOTAL_RECORDS    = 1_000_000;
    private static final int    INSERT_BATCH     = 5_000;
    private static final int    ES_BATCH         = 10_000;
    private static final int    BENCHMARK_RUNS   = 10;
    private static final String OWNER_PREFIX     = "bench_user_";

    private static final String[] KEYWORDS = {"spring", "react", "python", "api", "web",
                                               "django", "fastapi", "vue", "angular", "kotlin"};
    private static final String[] LANGUAGES = {"Java", "Python", "JavaScript", "TypeScript",
                                                "Go", "Rust", "C++", "Ruby", "Kotlin", "Swift"};

    // ── 셋업: DB 100만 건 삽입 → ES 인덱싱 ──────────────────────────────────

    @BeforeAll
    void setUp() {
        System.out.println("=================================================================");
        System.out.println("  벤치마크 셋업 시작 (총 " + String.format("%,d", TOTAL_RECORDS) + "건)");
        System.out.println("=================================================================");

        long t0 = System.currentTimeMillis();
        insertDummyRepos();
        System.out.printf("  DB 삽입 완료: %.1f초%n%n", (System.currentTimeMillis() - t0) / 1000.0);

        long t1 = System.currentTimeMillis();
        indexToElasticsearch();
        System.out.printf("  ES 인덱싱 완료: %.1f초%n%n", (System.currentTimeMillis() - t1) / 1000.0);

        System.out.println("  셋업 완료. 벤치마크를 시작합니다.\n");
    }

    private void insertDummyRepos() {
        System.out.println("  [1/2] PostgreSQL 삽입 중...");
        String sql = """
                INSERT INTO repos
                  (full_name, owner, name, description, star_count, fork_count,
                   open_issues_count, default_branch, tracked, language,
                   last_collected_at, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """;

        Random rnd = new Random(42);
        List<Object[]> batch = new ArrayList<>(INSERT_BATCH);

        for (int i = 0; i < TOTAL_RECORDS; i++) {
            String keyword = KEYWORDS[i % KEYWORDS.length];
            String owner   = OWNER_PREFIX + (i / 1000);
            String name    = keyword + "_lib_" + i;
            String desc    = "This is a " + keyword + " based repository, index=" + i;
            String lang    = LANGUAGES[rnd.nextInt(LANGUAGES.length)];

            batch.add(new Object[]{
                owner + "/" + name, owner, name, desc,
                rnd.nextInt(50000), rnd.nextInt(10000), rnd.nextInt(500),
                "main", false, lang,
                LocalDateTime.now(), LocalDateTime.now()
            });

            if (batch.size() == INSERT_BATCH) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
                if ((i + 1) % 100_000 == 0)
                    System.out.printf("    %,d / %,d 삽입%n", i + 1, TOTAL_RECORDS);
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);

        long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM repos", Long.class);
        System.out.printf("    → DB 총 %,d건 확인%n", count);
    }

    private void indexToElasticsearch() {
        System.out.println("  [2/2] Elasticsearch 인덱싱 중...");

        long total = 0;
        int  offset = 0;

        while (true) {
            List<RepoDocument> docs = jdbcTemplate.query(
                "SELECT id, full_name, owner, name, description, language, star_count, tracked " +
                "FROM repos ORDER BY id LIMIT ? OFFSET ?",
                (rs, rowNum) -> RepoDocument.builder()
                    .id(String.valueOf(rs.getLong("id")))
                    .fullName(rs.getString("full_name"))
                    .owner(rs.getString("owner"))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .language(rs.getString("language"))
                    .starCount(rs.getInt("star_count"))
                    .tracked(rs.getBoolean("tracked"))
                    .build(),
                ES_BATCH, offset
            );

            if (docs.isEmpty()) break;

            esRepository.saveAll(docs);
            total  += docs.size();
            offset += ES_BATCH;

            if (total % 100_000 == 0)
                System.out.printf("    %,d / %,d 인덱싱%n", total, TOTAL_RECORDS);
        }
        System.out.printf("    → ES 총 %,d건 인덱싱 완료%n", total);
    }

    // ── 정리: DB · ES 더미 데이터 삭제 ──────────────────────────────────────

    @AfterAll
    void tearDown() {
        System.out.println("\n  더미 데이터 정리 중...");
        jdbcTemplate.update("DELETE FROM repos WHERE owner LIKE '" + OWNER_PREFIX + "%'");
        esRepository.deleteAll();
        System.out.println("  정리 완료.");
    }

    // ── 벤치마크 ──────────────────────────────────────────────────────────────

    @Test
    void benchmarkSearchPerformance() {
        List<String> targets = List.of("spring", "react", "python", "api", "web");

        System.out.println("=================================================================");
        System.out.println("  검색 성능 벤치마크: PostgreSQL LIKE 풀스캔 vs Elasticsearch 역인덱스");
        System.out.printf("  데이터: %,d건  /  반복: %d회%n", TOTAL_RECORDS, BENCHMARK_RUNS);
        System.out.println("=================================================================\n");

        long totalDbNs = 0, totalEsNs = 0;

        for (String keyword : targets) {

            // 워밍업 (결과 캐시·JIT 안정화)
            for (int i = 0; i < 3; i++) {
                runDbLike(keyword);
                runEsSearch(keyword);
            }

            long[] dbTimes = new long[BENCHMARK_RUNS];
            long[] esTimes = new long[BENCHMARK_RUNS];

            for (int i = 0; i < BENCHMARK_RUNS; i++) {
                long s = System.nanoTime(); runDbLike(keyword);    dbTimes[i] = System.nanoTime() - s;
                     s = System.nanoTime(); runEsSearch(keyword);  esTimes[i] = System.nanoTime() - s;
            }

            LongSummaryStatistics dbStat = Arrays.stream(dbTimes).summaryStatistics();
            LongSummaryStatistics esStat = Arrays.stream(esTimes).summaryStatistics();
            totalDbNs += (long) dbStat.getAverage();
            totalEsNs += (long) esStat.getAverage();

            System.out.printf("── 키워드: \"%s\"  (예상 매칭: ~%,d건 / %,d건 전체)%n",
                keyword, TOTAL_RECORDS / KEYWORDS.length, TOTAL_RECORDS);
            printRow("PostgreSQL LIKE 풀스캔", dbStat, dbTimes);
            printRow("Elasticsearch 역인덱스 ", esStat, esTimes);
            System.out.printf("   → 속도 향상: %.1fx 빠름%n%n", dbStat.getAverage() / esStat.getAverage());
        }

        System.out.println("=================================================================");
        System.out.println("  최종 요약");
        System.out.println("=================================================================");
        System.out.printf("  PostgreSQL LIKE 평균: %8.2f ms%n", toMs(totalDbNs / targets.size()));
        System.out.printf("  Elasticsearch  평균: %8.2f ms%n", toMs(totalEsNs / targets.size()));
        System.out.printf("  전체 평균 속도 향상: %.1fx%n", (double) totalDbNs / totalEsNs);
        System.out.println("=================================================================");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private void runDbLike(String keyword) {
        repoRepository.searchByKeyword(keyword);
    }

    private void runEsSearch(String keyword) {
        var query = NativeQuery.builder()
            .withQuery(q -> q.multiMatch(mm -> mm
                .query(keyword)
                .fields(List.of("name^4", "fullName^4", "description^2", "owner"))
                .fuzziness("AUTO")))
            .build();
        SearchHits<RepoDocument> hits = elasticsearchOperations.search(query, RepoDocument.class);
    }

    private void printRow(String label, LongSummaryStatistics stat, long[] times) {
        System.out.printf("   [%s]  avg: %8.2f ms  min: %7.2f ms  max: %8.2f ms  p95: %8.2f ms%n",
            label, toMs((long) stat.getAverage()), toMs(stat.getMin()),
            toMs(stat.getMax()), toMs(percentile(times, 95)));
    }

    private long percentile(long[] times, int p) {
        long[] s = Arrays.copyOf(times, times.length);
        Arrays.sort(s);
        return s[(int) Math.ceil(p / 100.0 * s.length) - 1];
    }

    private double toMs(long nanos) { return nanos / 1_000_000.0; }
}
