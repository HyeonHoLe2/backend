package gitgalaxy.backend.service;

import gitgalaxy.backend.repository.ChunkEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 파이프라인: 질문 → 벡터 검색 → context 구성 → LLM 설명 생성.
 *
 * 흐름:
 *   1. 질문을 embedding → 쿼리 벡터
 *   2. chunk_embeddings에서 cosine 유사도 top-5 검색
 *   3. 검색 결과로 context 구성
 *   4. LLM에 prompt 전달 → 설명 반환
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final int TOP_K = 5;
    private static final int CANDIDATE_MULTIPLIER = 4; // Hybrid용 후보 배수

    // Hybrid 가중치: dense 70%, time 30% (intent에 따라 time 가중치 조정)
    private static final double W_DENSE = 0.7;
    private static final double W_TIME_LATEST = 0.5;  // 최신 intent: time 가중치 증가
    private static final double W_TIME_STABLE = 0.1;  // 안정적 intent: time 가중치 감소
    private static final double W_TIME_NEUTRAL = 0.3;

    private final EmbeddingService embeddingService;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final LlmService llmService;

    enum QueryIntent { LATEST, STABLE, NEUTRAL }

    /** 특정 repo에 대한 질문 답변 */
    public String explain(String owner, String repo, String question) {
        String queryVector = toQueryVector(question);

        List<Map<String, Object>> chunks = chunkEmbeddingRepository.findSimilar(queryVector, owner, repo, TOP_K);

        if (chunks.isEmpty()) {
            return "해당 repo(" + owner + "/" + repo + ")의 문서 데이터가 없습니다. "
                    + "먼저 POST /admin/collect 로 수집을 실행하세요.";
        }

        log.debug("RAG: {}/{} 질문='{}' → {}개 청크 검색됨", owner, repo, question, chunks.size());
        return llmService.chat(buildPrompt(owner + "/" + repo, question, buildContext(chunks)));
    }

    /** 전체 repo 대상 Hybrid RAG 검색 */
    public String explainGlobal(String question) {
        QueryIntent intent = detectIntent(question);
        log.debug("RAG global: 질문='{}' intent={}", question, intent);

        String queryVector = toQueryVector(question);

        // Dense Top-N*4 후보 추출 (embedded_at 포함)
        List<Map<String, Object>> candidates = chunkEmbeddingRepository
                .findSimilarGlobalWithTime(queryVector, TOP_K * CANDIDATE_MULTIPLIER);

        if (candidates.isEmpty()) {
            return "수집된 문서 데이터가 없습니다. POST /admin/collect 로 수집을 먼저 실행하세요.";
        }

        // 레포별 그룹핑 → 대표 청크 선택 → Hybrid 재랭킹
        List<Map<String, Object>> reranked = hybridRerank(candidates, intent, TOP_K);

        log.debug("RAG global: {}개 후보 → {}개 재랭킹 완료", candidates.size(), reranked.size());
        return llmService.chat(buildPrompt("GitHub repos", question, buildContext(reranked)));
    }

    // ────────────────────────────────────────────────

    private QueryIntent detectIntent(String question) {
        String q = question.toLowerCase();
        if (q.contains("최신") || q.contains("최근") || q.contains("trending") ||
            q.contains("요즘") || q.contains("새로운") || q.contains("latest") ||
            q.contains("new") || q.contains("recent")) {
            return QueryIntent.LATEST;
        }
        if (q.contains("검증된") || q.contains("안정적") || q.contains("오래된") ||
            q.contains("classic") || q.contains("stable") || q.contains("mature")) {
            return QueryIntent.STABLE;
        }
        return QueryIntent.NEUTRAL;
    }

    private List<Map<String, Object>> hybridRerank(
            List<Map<String, Object>> candidates, QueryIntent intent, int topK) {

        // time_score 정규화를 위한 min/max 계산
        long minTime = Long.MAX_VALUE, maxTime = Long.MIN_VALUE;
        for (Map<String, Object> c : candidates) {
            long t = toEpoch(c.get("embedded_at"));
            if (t < minTime) minTime = t;
            if (t > maxTime) maxTime = t;
        }
        long timeRange = maxTime - minTime;

        double wTime = switch (intent) {
            case LATEST -> W_TIME_LATEST;
            case STABLE -> W_TIME_STABLE;
            case NEUTRAL -> W_TIME_NEUTRAL;
        };
        double wDense = 1.0 - wTime;

        // 레포별 그룹핑 → 각 레포에서 dense 점수 가장 높은 청크를 대표로 선택
        Map<String, Map<String, Object>> repByRepo = new LinkedHashMap<>();
        for (Map<String, Object> chunk : candidates) {
            String repoKey = chunk.get("repo_owner") + "/" + chunk.get("repo_name");
            repByRepo.merge(repoKey, chunk, (existing, incoming) -> {
                double existScore = toDouble(existing.get("similarity"));
                double newScore = toDouble(incoming.get("similarity"));
                return newScore > existScore ? incoming : existing;
            });
        }

        // 대표 청크들에 Hybrid 점수 계산 후 재랭킹
        return repByRepo.values().stream()
                .map(chunk -> {
                    double denseScore = toDouble(chunk.get("similarity"));
                    double normalizedTime = timeRange == 0 ? 0.5 :
                            (double)(toEpoch(chunk.get("embedded_at")) - minTime) / timeRange;
                    // STABLE intent는 오래된 것이 높은 점수
                    double timeScore = intent == QueryIntent.STABLE ? 1.0 - normalizedTime : normalizedTime;
                    double hybridScore = wDense * denseScore + wTime * timeScore;

                    Map<String, Object> scored = new HashMap<>(chunk);
                    scored.put("hybrid_score", hybridScore);
                    return scored;
                })
                .sorted(Comparator.comparingDouble(c -> -toDouble(c.get("hybrid_score"))))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private String toQueryVector(String question) {
        float[] embedding = embeddingService.embed(question);
        return EmbeddingService.toVectorString(embedding);
    }

    private String buildContext(List<Map<String, Object>> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> c = chunks.get(i);
            sb.append("=== 문서 ").append(i + 1).append(" ===\n")
              .append("출처: ").append(c.get("repo_owner")).append("/").append(c.get("repo_name")).append('\n')
              .append("파일: ").append(c.get("file_path")).append('\n')
              .append("섹션: ").append(c.get("heading")).append('\n')
              .append(c.get("content")).append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String target, String question, String context) {
        return """
                당신은 GitHub 레포지토리 분석 전문가입니다.
                아래는 '%s' 레포지토리의 실제 문서 내용입니다.

                [참고 문서]
                %s

                위 문서를 바탕으로 다음 질문에 답하세요:
                %s

                - 반드시 위 문서 내용을 근거로 답하세요.
                - 문서에 없는 내용은 "문서에 명시되지 않았습니다"라고 하세요.
                - 한국어로 답변하세요.
                """.formatted(target, context, question);
    }

    private long toEpoch(Object val) {
        if (val instanceof Timestamp ts) return ts.getTime();
        if (val instanceof java.util.Date d) return d.getTime();
        return 0L;
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
