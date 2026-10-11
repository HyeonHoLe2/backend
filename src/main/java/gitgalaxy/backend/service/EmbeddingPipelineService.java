package gitgalaxy.backend.service;

import gitgalaxy.backend.metrics.CollectorMetrics;
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
    private final CollectorMetrics collectorMetrics;
    private final gitgalaxy.backend.config.VertexAiProperties vertexProps;
    private final RagAnswerCache ragAnswerCache;

    public void embedAndStore(List<ChunkDocument> chunks) {
        if (!embeddingService.isConfigured()) {
            log.warn("GEMINI_API_KEY 미설정 → 임베딩 스킵 ({} 청크)", chunks.size());
            return;
        }

        // 이미 저장된 청크는 임베딩 API에 다시 보내지 않음 → 존재 확인은 한 번에 (청크마다 왕복하지 않음)
        java.util.Set<String> existing = chunkEmbeddingRepository.findExistingChunkIds(
                chunks.stream().map(ChunkDocument::getChunkId).distinct().toList());
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<ChunkDocument> toEmbed = chunks.stream()
                .filter(chunk -> !existing.contains(chunk.getChunkId()) && seen.add(chunk.getChunkId()))
                .toList();

        int skipped = chunks.size() - toEmbed.size();
        java.util.concurrent.atomic.AtomicInteger stored = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Set<String> changedRepos = java.util.concurrent.ConcurrentHashMap.newKeySet();
        collectorMetrics.embeddingChunks("skipped_existing", skipped);

        int totalBatches = (int) Math.ceil((double) toEmbed.size() / BATCH_SIZE);
        log.info("배치 임베딩 시작: 총 {}개 청크 → {}번 API 호출 예정", toEmbed.size(), totalBatches);

        // 묶음마다 임베딩 호출 + 저장을 동시에 진행 (묶음끼리 독립, Vertex 응답 대기 시간이 대부분)
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, vertexProps.getEmbeddingConcurrency()));
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < toEmbed.size(); i += BATCH_SIZE) {
            List<ChunkDocument> batch = toEmbed.subList(i, Math.min(i + BATCH_SIZE, toEmbed.size()));
            int batchNum = (i / BATCH_SIZE) + 1;
            futures.add(pool.submit(() -> {
                try {
                    List<String> texts = batch.stream().map(ChunkDocument::getContent).toList();
                    List<float[]> vectors = collectorMetrics.step("7a.청크 임베딩(Vertex)", () -> embeddingService.embedBatch(texts));

                    // 임베딩 API 묶음 단위 그대로 DB에도 한 번에 저장 (건별 INSERT 왕복 제거)
                    collectorMetrics.step("7b.청크 DB 저장", () -> chunkEmbeddingRepository.batchUpsert(batch,
                            vectors.stream().map(EmbeddingService::toVectorString).toList()));
                    stored.addAndGet(batch.size());
                    batch.forEach(c -> changedRepos.add(c.getRepoOwner() + "/" + c.getRepoName()));
                    collectorMetrics.embeddingChunks("saved", batch.size());
                    log.info("배치 {}/{} 완료 ({}개)", batchNum, totalBatches, batch.size());
                } catch (Exception e) {
                    collectorMetrics.embeddingChunks("failed", batch.size());
                    log.warn("배치 {}/{} 실패 (스킵): {}", batchNum, totalBatches, e.getMessage());
                }
            }));
        }
        try {
            for (java.util.concurrent.Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("임베딩 작업 오류: {}", e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }

        // 문서가 바뀐 레포의 RAG 답변과 전체 검색 답변 무효화
        changedRepos.forEach(r -> ragAnswerCache.evictRepo(r.substring(0, r.indexOf('/')), r.substring(r.indexOf('/') + 1)));
        if (!changedRepos.isEmpty()) ragAnswerCache.evictGlobal();

        log.info("임베딩 완료: 저장={} / 스킵(기존)={} / 전체={} / API 호출={} (동시 {}개)", stored.get(), skipped, chunks.size(), totalBatches,
                vertexProps.getEmbeddingConcurrency());
    }
}
