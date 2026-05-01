package gitgalaxy.backend.service;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.ChunkEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 청크 목록 → OpenAI 임베딩 → pgvector 저장 파이프라인.
 * OPENAI_API_KEY 미설정 시 경고 후 스킵.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingPipelineService {

    private final EmbeddingService embeddingService;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;

    public void embedAndStore(List<ChunkDocument> chunks) {
        if (!embeddingService.isConfigured()) {
            log.warn("GEMINI_API_KEY 미설정 → 임베딩 스킵 ({} 청크)", chunks.size());
            return;
        }

        int stored = 0;
        int skipped = 0;

        for (ChunkDocument chunk : chunks) {
            try {
                if (chunkEmbeddingRepository.existsByChunkId(chunk.getChunkId())) {
                    skipped++;
                    continue;
                }

                float[] vector = embeddingService.embed(chunk.getContent());
                chunkEmbeddingRepository.upsert(chunk, EmbeddingService.toVectorString(vector));
                stored++;

                // OpenAI RPM 한도 방지 (3000 req/min → 20ms 간격)
                Thread.sleep(20);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("임베딩 인터럽트: {}", chunk.getChunkId());
                break;
            } catch (Exception e) {
                log.warn("임베딩 실패 (청크 스킵): {} → {}", chunk.getChunkId(), e.getMessage());
            }
        }

        log.info("임베딩 완료: 저장={} / 스킵(기존)={} / 전체={}", stored, skipped, chunks.size());
    }
}
