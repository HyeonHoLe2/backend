package gitgalaxy.backend.service;

import gitgalaxy.backend.config.VertexAiProperties;
import gitgalaxy.backend.metrics.CollectorMetrics;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.model.RepoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 신규 발견 repo 온보딩.
 * GitHub 메타 수집 → repos upsert(tracked=true) → AI 요약 → README 청크 추출.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoOnboardService {

    private final GithubClient githubClient;
    private final RepoSaveService repoSaveService;
    private final AiSummerize aiSummerize;
    private final ChunkingService chunkingService;
    private final VertexAiProperties vertexAiProperties;
    private final CollectorMetrics collectorMetrics;
    private final StarStatsService starStatsService;
    private final RepoEmbeddingService repoEmbeddingService;

    /**
     * 배경 repo 가벼운 등록: GraphQL 50개 묶음으로 메타·토픽·README를 한 번에 받아
     * repos 저장(tracked=true → 이후 시간별 활동 집계) + 레포 단위 임베딩 1개.
     * README 청크 임베딩·AI 요약은 하지 않음. 등록된 repo 이름 반환.
     */
    public List<String> registerLight(List<String> fullNames) {
        if (fullNames.isEmpty()) return List.of();
        Map<String, gitgalaxy.backend.model.RepoProfile> profiles =
                collectorMetrics.step("5a.레포 프로필 조회(GraphQL)", () -> starStatsService.fetchProfiles(fullNames));
        long saveStart = System.nanoTime();
        Map<String, gitgalaxy.backend.model.RepoProfile> saved = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, gitgalaxy.backend.model.RepoProfile> e : profiles.entrySet()) {
            String[] p = e.getKey().split("/", 2);
            try {
                repoSaveService.saveOrUpdate(p[0], p[1], e.getValue().meta());
                saved.put(e.getKey(), e.getValue());
                collectorMetrics.repoOnboard("success");
            } catch (Exception ex) {
                collectorMetrics.repoOnboard("failed");
                log.warn("repo 가벼운 등록 실패 {}: {}", e.getKey(), ex.getMessage());
            }
        }
        collectorMetrics.recordStep("5b.레포 DB 저장", System.nanoTime() - saveStart);
        List<String> embedded = collectorMetrics.step("5d.레포 벡터 임베딩", () -> repoEmbeddingService.embedProfiles(saved));
        log.info("RepoRegisterLight: {}/{}개 등록, 레포 임베딩 {}개 (GraphQL {}회)", saved.size(), fullNames.size(),
                embedded.size(), (fullNames.size() + 24) / 25);
        return new ArrayList<>(saved.keySet());
    }

    /** 여러 repo 온보딩 → 임베딩할 README 청크 반환 */
    public List<ChunkDocument> onboardAll(List<String> fullNames) {
        List<ChunkDocument> allChunks = new ArrayList<>();
        int onboarded = 0;

        for (String fullName : fullNames) {
            // rate limit 여유분 도달 → 나머지는 생략 (활동이 이어지면 다음 시간대에 다시 후보가 됨)
            if (!githubClient.hasBudget()) {
                log.warn("GitHub rate limit 여유분 도달 (남은 요청 {}) → 온보딩 {}개 생략",
                        githubClient.getRateLimitRemaining(), fullNames.size() - onboarded);
                break;
            }
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2) continue;
            try {
                allChunks.addAll(onboard(parts[0], parts[1]));
                onboarded++;
                collectorMetrics.repoOnboard("success");
            } catch (Exception e) {
                collectorMetrics.repoOnboard("failed");
                log.warn("repo 온보딩 실패 {}: {}", fullName, e.getMessage());
            }
        }

        log.info("RepoOnboard: {}/{}개 repo 온보딩, README 청크 {}개", onboarded, fullNames.size(), allChunks.size());
        return allChunks;
    }

    private List<ChunkDocument> onboard(String owner, String name) {
        RepoMeta meta = githubClient.getRepoMeta(owner, name);
        repoSaveService.saveOrUpdate(owner, name, meta);

        // 기존 요약이 있으면 재사용
        if (vertexAiProperties.isSummaryEnabled()) {
            try {
                aiSummerize.summarize(owner, name);
            } catch (Exception e) {
                log.warn("[{}/{}] 요약 실패 (스킵): {}", owner, name, e.getMessage());
            }
        }

        return fetchReadmeChunks(owner, name, meta.defaultBranch());
    }

    /** default branch의 README.md를 raw로 다운로드 → chunk. 없으면 빈 리스트 */
    private List<ChunkDocument> fetchReadmeChunks(String owner, String name, String branch) {
        try {
            String readme = githubClient.getRawContent(owner, name, branch, "README.md");
            if (readme == null || readme.isBlank()) return List.of();
            String fileUrl = "https://github.com/" + owner + "/" + name + "#readme";
            return chunkingService.chunk(readme, owner, name, "README.md", fileUrl);
        } catch (Exception e) {
            log.debug("README fetch 실패 {}/{}/{}: {}", owner, name, branch, e.getMessage());
            return List.of();
        }
    }
}
