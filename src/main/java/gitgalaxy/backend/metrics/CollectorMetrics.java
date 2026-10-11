package gitgalaxy.backend.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 수집 파이프라인 지표 (Prometheus: /actuator/prometheus 에 gitgalaxy_* 로 노출).
 * 카운터는 앱 재시작 시 0부터 다시 시작 → Grafana에서는 increase()/rate()로 조회.
 */
@Component
public class CollectorMetrics {

    private final MeterRegistry registry;
    private final AtomicLong githubRateLimitRemaining = new AtomicLong(-1);

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("gitgalaxy.github.ratelimit.remaining", githubRateLimitRemaining, AtomicLong::get)
                .description("GitHub API 남은 요청 수 (마지막 응답 헤더 기준, -1 = 아직 호출 없음)")
                .register(registry);
    }

    // ─── GH Archive ───────────────────────────────────────

    /** 시간별 파일 다운로드 1회 시도. outcome: success | not_found | error */
    public void ghArchiveDownload(String outcome, long startNanos) {
        timer("gitgalaxy.gharchive.download", "GH Archive 파일 다운로드 시도", "outcome", outcome)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /** 시간대 1개 전체 처리(다운로드~임베딩~스코어). outcome: success | failed */
    public void ghArchiveHour(String outcome, long startNanos) {
        timer("gitgalaxy.gharchive.hour", "GH Archive 시간대 처리", "outcome", outcome)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    public void ghArchiveEvents(long count) {
        counter("gitgalaxy.gharchive.events", "읽은 GH Archive 이벤트 수").increment(count);
    }

    // ─── Repo ─────────────────────────────────────────────

    public void reposDiscovered(int count) {
        counter("gitgalaxy.repo.discovered", "신규 발견 repo 후보 수").increment(count);
    }

    /** result: success | failed */
    public void repoOnboard(String result) {
        counter("gitgalaxy.repo.onboard", "repo 온보딩", "result", result).increment();
    }

    /** result: updated | renamed | merged | untracked | failed */
    public void repoMetaRefresh(String result) {
        counter("gitgalaxy.repo.meta.refresh", "repo 메타 갱신", "result", result).increment();
    }

    // ─── GitHub API ───────────────────────────────────────

    /** endpoint: repo | compare | commit | tree | raw | other, status: HTTP 코드 또는 error */
    public void githubRequest(String endpoint, String status, long startNanos) {
        timer("gitgalaxy.github.requests", "GitHub 요청", "endpoint", endpoint, "status", status)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    public void githubRateLimitRemaining(long remaining) {
        githubRateLimitRemaining.set(remaining);
    }

    /** push 커밋 조회. result: success | failed | skipped_limit */
    public void commitFetch(String result) {
        counter("gitgalaxy.github.commit.fetch", "push 커밋 조회", "result", result).increment();
    }

    // ─── Embedding (Vertex AI) ────────────────────────────

    /** 배치 임베딩 API 호출 1회. result: success | failed */
    public void embeddingRequest(String result, long startNanos) {
        timer("gitgalaxy.embedding.requests", "Vertex AI 임베딩 API 호출", "result", result)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /** result: saved | skipped_existing | failed */
    public void embeddingChunks(String result, int count) {
        if (count <= 0) return;
        counter("gitgalaxy.embedding.chunks", "임베딩 청크", "result", result).increment(count);
    }

    // ─── ES 동기화 (Polling Publisher) ─────────────────────

    /** result: indexed | failed */
    public void esSync(String result, int count) {
        if (count <= 0) return;
        counter("gitgalaxy.es.sync", "ES 동기화 repo 수", "result", result).increment(count);
    }

    // ─── 배치 단계별 소요 시간 ─────────────────────────────

    /**
     * 배치 1회 동안 단계별 누적 시간. 병렬 작업 스레드는 배치 스레드가 만들므로 InheritableThreadLocal로 같은 요약을 공유.
     * 병렬 단계는 스레드별 시간의 합이라 실제 경과 시간보다 클 수 있음 (경과 시간은 감싸는 상위 단계로 확인).
     */
    private final InheritableThreadLocal<java.util.Map<String, long[]>> runSteps = new InheritableThreadLocal<>();

    /** 배치 시작: 단계 누적 초기화 */
    public void startRun() {
        runSteps.set(new java.util.LinkedHashMap<>());
    }

    /** 단계 시간 기록: gitgalaxy_batch_step_seconds{step} + 배치 요약용 누적 (같은 단계가 여러 번이면 합산) */
    public <T> T step(String step, java.util.function.Supplier<T> work) {
        long t0 = System.nanoTime();
        try {
            return work.get();
        } finally {
            recordStep(step, System.nanoTime() - t0);
        }
    }

    /** 이미 측정한 구간 기록 (루프 안에서 따로 잰 시간 등) */
    public void recordStep(String step, long nanos) {
        timer("gitgalaxy.batch.step", "배치 단계 소요 시간", "step", step).record(Duration.ofNanos(nanos));
        java.util.Map<String, long[]> m = runSteps.get();
        if (m != null) {
            synchronized (m) {
                long[] v = m.computeIfAbsent(step, k -> new long[2]);
                v[0] += nanos;
                v[1]++;
            }
        }
    }

    public void step(String step, Runnable work) {
        step(step, () -> { work.run(); return null; });
    }

    /** 배치 끝: "단계=초(횟수)" 한 줄 요약 */
    public String endRun() {
        java.util.Map<String, long[]> m = runSteps.get();
        runSteps.remove();
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        synchronized (m) { m.forEach((k, v) -> sb.append(k).append('=').append(String.format("%.1fs", v[0] / 1e9))
                .append(v[1] > 1 ? "(" + v[1] + "회)" : "").append("  ")); }
        return sb.toString().strip();
    }

    // ─── RAG ──────────────────────────────────────────────

    /** RAG 답변 캐시 조회. scope: repo | global, result: hit | miss | error */
    public void ragCache(String scope, String result) {
        counter("gitgalaxy.rag.cache", "RAG 답변 캐시 조회", "scope", scope, "result", result).increment();
    }

    // ─── helpers ──────────────────────────────────────────

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    private Timer timer(String name, String description, String... tags) {
        return Timer.builder(name).description(description).tags(tags).register(registry);
    }
}
