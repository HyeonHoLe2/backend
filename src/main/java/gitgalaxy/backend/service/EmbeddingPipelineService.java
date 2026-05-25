package gitgalaxy.backend.service;

import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.ChunkEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingPipelineService {

    private static final int BATCH_SIZE = 20;

    private final EmbeddingService embeddingService;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;

    public void embedAndStore(List<ChunkDocument> chunks) {
        if (!embeddingService.isConfigured()) {
            log.warn("GEMINI_API_KEY 미설정 → 임베딩 스킵 ({} 청크)", chunks.size());
            return;
        }

        List<ChunkDocument> toEmbed = chunks.stream()
                .filter(chunk -> !chunkEmbeddingRepository.existsByChunkId(chunk.getChunkId()))
                .toList();

        int skipped = chunks.size() - toEmbed.size();
        int stored = 0;

        int totalBatches = (int) Math.ceil((double) toEmbed.size() / BATCH_SIZE);
        log.info("배치 임베딩 시작: 총 {}개 청크 → {}번 API 호출 예정", toEmbed.size(), totalBatches);

        for (int i = 0; i < toEmbed.size(); i += BATCH_SIZE) {
            List<ChunkDocument> batch = toEmbed.subList(i, Math.min(i + BATCH_SIZE, toEmbed.size()));
            int batchNum = (i / BATCH_SIZE) + 1;
            try {
                List<String> texts = batch.stream().map(ChunkDocument::getContent).toList();
                List<float[]> vectors = embeddingService.embedBatch(texts);

                for (int j = 0; j < batch.size(); j++) {
                    chunkEmbeddingRepository.upsert(batch.get(j), EmbeddingService.toVectorString(vectors.get(j)));
                    stored++;
                }
                log.info("배치 {}/{} 완료 ({}개)", batchNum, totalBatches, batch.size());
            } catch (Exception e) {
                log.warn("배치 {}/{} 실패 (스킵): {}", batchNum, totalBatches, e.getMessage());
            }
        }

        log.info("임베딩 완료: 저장={} / 스킵(기존)={} / 전체={} / API 호출={}", stored, skipped, chunks.size(), totalBatches);
    }
}
