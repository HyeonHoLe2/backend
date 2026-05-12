package gitgalaxy.backend.service;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import gitgalaxy.backend.config.VertexAiProperties;
import gitgalaxy.backend.entity.RepoHourlyMetrics;
import gitgalaxy.backend.entity.RepoTrendReason;
import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoTrendReasonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendReasonService {

    private static final int CACHE_TTL_HOURS = 6;

    private final RepoHourlyMetricsRepository metricsRepository;
    private final RepoTrendReasonRepository trendReasonRepository;
    private final VertexAiProperties vertexAiProperties;
    private final JdbcTemplate jdbc;

    @Transactional
    public RepoTrendReason getTrendReason(String owner, String repo) {
        // 캐시 확인 (6h TTL)
        RepoTrendReason cached = trendReasonRepository.findByOwnerAndRepo(owner, repo).orElse(null);
        if (cached != null && cached.getGeneratedAt().isAfter(Instant.now().minus(CACHE_TTL_HOURS, ChronoUnit.HOURS))) {
            return cached;
        }

        // 속도 계산
        LocalDateTime now = LocalDateTime.now();
        List<RepoHourlyMetrics> last24h = metricsRepository
                .findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(owner, repo, now.minusHours(24), now);
        List<RepoHourlyMetrics> last7d = metricsRepository
                .findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(owner, repo, now.minusDays(7), now);

        double avg24h = last24h.stream().mapToDouble(this::totalEvents).average().orElse(0.0);
        double avg7d  = last7d.stream().mapToDouble(this::totalEvents).average().orElse(0.0);

        double changeRate = avg7d < 0.1 ? 0.0 : (avg24h - avg7d) / avg7d * 100.0;
        String trend;
        if (changeRate >= 20.0)       trend = "상승";
        else if (changeRate <= -20.0) trend = "하락";
        else                          trend = "보합";

        // README/코드 청크 컨텍스트 (최대 3개)
        String context = fetchRepoContext(owner, repo);

        // 최근 24h 메트릭 요약
        int totalWatch   = last24h.stream().mapToInt(RepoHourlyMetrics::getWatch).sum();
        int totalCommit  = last24h.stream().mapToInt(RepoHourlyMetrics::getCommitCount).sum();
        int totalPr      = last24h.stream().mapToInt(r -> r.getPrCreated() + r.getPrMerged()).sum();
        int totalIssue   = last24h.stream().mapToInt(r -> r.getIssueOpened() + r.getIssueClosed()).sum();

        String reason = generateReason(owner, repo, trend, changeRate, totalWatch, totalCommit, totalPr, totalIssue, context);

        // 저장/업데이트
        RepoTrendReason result = cached != null ? cached : new RepoTrendReason();
        result.setOwner(owner);
        result.setRepo(repo);
        result.setTrend(trend);
        result.setChangeRate(Math.round(changeRate * 10.0) / 10.0);
        result.setReason(reason);
        result.setGeneratedAt(Instant.now());
        return trendReasonRepository.save(result);
    }

    private double totalEvents(RepoHourlyMetrics r) {
        return r.getWatch() + r.getCommitCount() + r.getPrCreated()
                + r.getPrMerged() + r.getIssueOpened() + r.getIssueClosed();
    }

    private String fetchRepoContext(String owner, String repo) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT heading, content FROM chunk_embeddings
                    WHERE repo_owner = ? AND repo_name = ?
                    ORDER BY embedded_at DESC
                    LIMIT 3
                    """, owner, repo);
            return rows.stream()
                    .map(r -> {
                        String heading = (String) r.get("heading");
                        String content = (String) r.get("content");
                        String snippet = content != null && content.length() > 300
                                ? content.substring(0, 300) + "..." : content;
                        return (heading != null && !heading.isBlank() ? "## " + heading + "\n" : "") + snippet;
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            log.debug("chunk 컨텍스트 조회 실패 {}/{}: {}", owner, repo, e.getMessage());
            return "";
        }
    }

    private String generateReason(String owner, String repo, String trend, double changeRate,
                                   int watch, int commit, int pr, int issue, String context) {
        String contextSection = context.isBlank() ? "" : "\n\n[레포 README 발췌]\n" + context;
        String prompt = String.format("""
                당신은 GitHub 트렌드를 분석하는 전문가입니다.
                다음 데이터를 바탕으로 '%s/%s' 레포가 지금 %s하고 있는 이유를 2~3문장으로 설명해주세요.

                [최근 24시간 활동]
                - Watch(스타) 이벤트: %d건
                - 커밋: %d건
                - PR: %d건
                - 이슈: %d건
                - 7일 평균 대비 변화율: %.1f%%
                %s

                기술적이고 구체적으로, 한국어로 설명해주세요.
                """,
                owner, repo, trend, watch, commit, pr, issue, changeRate, contextSection);

        try (VertexAI vertexAI = new VertexAI(vertexAiProperties.getProject(), vertexAiProperties.getLocation())) {
            GenerativeModel model = new GenerativeModel(vertexAiProperties.getModel(), vertexAI);
            GenerateContentResponse response = model.generateContent(prompt);
            return response.getCandidates(0).getContent().getParts(0).getText().strip();
        } catch (Exception e) {
            log.warn("LLM 트렌드 이유 생성 실패 {}/{}: {}", owner, repo, e.getMessage());
            return trend.equals("상승")
                    ? "최근 24시간 동안 활동량이 7일 평균 대비 " + String.format("%.1f", changeRate) + "% 증가했습니다."
                    : trend.equals("하락")
                    ? "최근 24시간 동안 활동량이 7일 평균 대비 " + String.format("%.1f", Math.abs(changeRate)) + "% 감소했습니다."
                    : "최근 활동량이 7일 평균과 비슷한 수준을 유지하고 있습니다.";
        }
    }
}
