package gitgalaxy.backend.service;

import gitgalaxy.backend.repository.ChunkEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    private final EmbeddingService embeddingService;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final LlmService llmService;

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

    /** 전체 repo 대상 글로벌 검색 */
    public String explainGlobal(String question) {
        String queryVector = toQueryVector(question);

        List<Map<String, Object>> chunks = chunkEmbeddingRepository.findSimilarGlobal(queryVector, TOP_K);

        if (chunks.isEmpty()) {
            return "수집된 문서 데이터가 없습니다. POST /admin/collect 로 수집을 먼저 실행하세요.";
        }

        log.debug("RAG global: 질문='{}' → {}개 청크 검색됨", question, chunks.size());
        return llmService.chat(buildPrompt("GitHub repos", question, buildContext(chunks)));
    }

    // ────────────────────────────────────────────────

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
}
