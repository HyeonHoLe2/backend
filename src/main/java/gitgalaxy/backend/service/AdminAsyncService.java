package gitgalaxy.backend.service;

import gitgalaxy.backend.model.GhArchiveResult;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAsyncService {

    private final GhArchiveService ghArchiveService;
    private final TrendingRssService trendingRssService;
    private final ScoreService scoreService;
    private final RepoRepository repoRepository;
    private final EmbeddingPipelineService embeddingPipelineService;

    @Async
    public void processGhArchiveAsync(LocalDateTime hourBucket) {
        try {
            log.info("GH Archive 비동기 처리 시작: {}", hourBucket);
            GhArchiveResult result = ghArchiveService.processHour(hourBucket);
            embeddingPipelineService.embedAndStore(result.chunks());

            List<String> affectedList = result.affectedRepos().stream()
                    .filter(f -> f.contains("/")).toList();
            scoreService.calculateAndSaveBatch(affectedList, hourBucket);
            log.info("GH Archive 비동기 처리 완료: chunks={}, 스코어 계산 레포={}", result.chunks().size(), affectedList.size());
        } catch (Exception e) {
            log.error("GH Archive 비동기 처리 실패: {}", e.getMessage(), e);
        }
    }

    @Async
    public void runTrendingAsync() {
        try {
            log.info("Trending RSS 비동기 처리 시작");
            var chunks = trendingRssService.discoverAndUpsert();
            embeddingPipelineService.embedAndStore(chunks);
            log.info("Trending RSS 비동기 처리 완료: chunks={}", chunks.size());
        } catch (Exception e) {
            log.error("Trending RSS 비동기 처리 실패: {}", e.getMessage(), e);
        }
    }

    @Async
    public void recalculateScoresAsync() {
        try {
            log.info("스코어 재계산 비동기 처리 시작");
            long[] count = {0};
            repoRepository.findByTrackedTrue().forEach(repo -> {
                scoreService.calculateAndSave(repo.getOwner(), repo.getName(), null);
                count[0]++;
            });
            log.info("스코어 재계산 비동기 처리 완료: {}개", count[0]);
        } catch (Exception e) {
            log.error("스코어 재계산 비동기 처리 실패: {}", e.getMessage(), e);
        }
    }
}
