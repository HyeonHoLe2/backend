package gitgalaxy.backend.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GithubCollectorProperties;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.model.RepoInput;
import gitgalaxy.backend.model.RepoMeta;
import gitgalaxy.backend.model.RepoResult;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 전체 수집 흐름 조율 (Orchestrator).
 *
 * 흐름:
 *   1. repo 목록 로드 (JSON / CSV)
 *   2. 각 repo 순차 처리:
 *      - skip 여부 확인
 *      - default branch 조회
 *      - tree recursive 조회
 *      - 문서 파일 필터링
 *      - 파일별 raw content 다운로드 + chunking
 *      - JSONL 저장
 *   3. batch_summary 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoCollectorService {

    private final GithubCollectorProperties props;
    private final GithubClient githubClient;
    private final FileSelector fileSelector;
    private final ChunkingService chunkingService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final RepoRepository repoRepository;
    private final EmbeddingPipelineService embeddingPipelineService;

    // ────────────────────────────────────────────────
    // Batch entry
    // ────────────────────────────────────────────────

    public List<RepoResult> runBatch() {
        List<RepoInput> repos = loadRepoList();
        if (repos.isEmpty()) {
            log.warn("처리할 repo가 없습니다. repo 목록 파일 확인: {}", props.getRepoListPath());
            return List.of();
        }

        log.info("=== Batch 시작: {}개 repo 처리 ===", repos.size());
        List<RepoResult> results = new ArrayList<>();

        for (int i = 0; i < repos.size(); i++) {
            RepoInput input = repos.get(i);
            RepoResult result = collectRepo(input);
            results.add(result);
            log.info("[{}/{}] {} → status={}, files={}, chunks={}, {}ms",
                    i + 1, repos.size(),
                    input.fullName(),
                    result.getStatus(),
                    result.getFileCount(),
                    result.getChunkCount(),
                    result.getDurationMs());
        }

        storageService.saveBatchSummary(results);
        return results;
    }

    // ────────────────────────────────────────────────
    // Repo 단위 수집 (fail isolation)
    // ────────────────────────────────────────────────

    public RepoResult collectRepo(RepoInput input) {
        long start = System.currentTimeMillis();

        // ── skip 판단 ──
        if (props.isSkipExisting() && storageService.isAlreadyCollected(input.owner(), input.repo())) {
            log.info("SKIP (이미 수집됨): {}", input.fullName());
            return RepoResult.builder()
                    .owner(input.owner()).repo(input.repo())
                    .status("skipped")
                    .durationMs(elapsed(start))
                    .processedAt(LocalDateTime.now())
                    .build();
        }

        try {
            // ── repo 메타 조회 (branch + star + description) ──
            RepoMeta meta = githubClient.getRepoMeta(input.owner(), input.repo());
            String branch = meta.defaultBranch();
            log.debug("{}: branch={}, stars={}", input.fullName(), branch, meta.stargazersCount());

            // ── tree recursive 조회 ──
            List<String> allPaths = githubClient.getRepoTree(input.owner(), input.repo(), branch);

            // ── 문서 파일 필터링 ──
            List<String> docPaths = fileSelector.filterDocFiles(allPaths);
            log.info("{}: 문서 파일 {}개 선택 (전체 {}개)", input.fullName(), docPaths.size(), allPaths.size());

            // ── 파일별 다운로드 & chunking ──
            List<ChunkDocument> allChunks = new ArrayList<>();
            for (String filePath : docPaths) {
                try {
                    String content = githubClient.getRawContent(input.owner(), input.repo(), branch, filePath);
                    String fileUrl = buildGithubUrl(input.owner(), input.repo(), branch, filePath);
                    List<ChunkDocument> chunks = chunkingService.chunk(
                            content, input.owner(), input.repo(), filePath, fileUrl);
                    allChunks.addAll(chunks);
                } catch (Exception e) {
                    // 파일 단위 실패 → 경고만 남기고 계속 진행
                    log.warn("{}/{}: 파일 처리 실패 → {}", input.fullName(), filePath, e.getMessage());
                }
            }

            // ── JSONL 저장 ──
            storageService.saveChunks(input.owner(), input.repo(), allChunks);

            // ── Repo 엔티티 DB 저장 ──
            upsertRepo(input, meta);

            // ── 임베딩 파이프라인 (OPENAI_API_KEY 설정 시) ──
            embeddingPipelineService.embedAndStore(allChunks);

            return RepoResult.builder()
                    .owner(input.owner()).repo(input.repo())
                    .status("success")
                    .fileCount(docPaths.size())
                    .chunkCount(allChunks.size())
                    .durationMs(elapsed(start))
                    .processedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("{}: 수집 실패 → {}", input.fullName(), e.getMessage(), e);
            return RepoResult.builder()
                    .owner(input.owner()).repo(input.repo())
                    .status("failed")
                    .errorMessage(e.getMessage())
                    .durationMs(elapsed(start))
                    .processedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ────────────────────────────────────────────────
    // Repo 엔티티 upsert
    // ────────────────────────────────────────────────

    private void upsertRepo(RepoInput input, RepoMeta meta) {
        Repo repo = repoRepository.findByFullName(input.fullName())
                .orElse(Repo.builder()
                        .fullName(input.fullName())
                        .owner(input.owner())
                        .name(input.repo())
                        .createdAt(LocalDateTime.now())
                        .build());

        repo.setDescription(meta.description());
        repo.setStarCount(meta.stargazersCount());
        repo.setDefaultBranch(meta.defaultBranch());
        repo.setTracked(true);
        repo.setLastCollectedAt(LocalDateTime.now());
        repoRepository.save(repo);
    }

    // ────────────────────────────────────────────────
    // Repo 목록 로드
    // ────────────────────────────────────────────────

    public List<RepoInput> loadRepoList() {
        // 1. DB에서 추적 중인 repo 로드
        List<Repo> trackedRepos = repoRepository.findByTrackedTrue();
        if (!trackedRepos.isEmpty()) {
            log.info("DB에서 추적 repo {}개 로드", trackedRepos.size());
            return trackedRepos.stream()
                    .map(r -> new RepoInput(r.getOwner(), r.getName()))
                    .toList();
        }

        // 2. fallback: repos.json / repos.csv
        String pathStr = props.getRepoListPath();
        Path filePath = Path.of(pathStr);

        if (!Files.exists(filePath)) {
            log.warn("DB에 tracked repo 없음, repo 목록 파일도 없음: {}", filePath.toAbsolutePath());
            return List.of();
        }

        log.info("DB에 tracked repo 없음 → 파일에서 로드: {}", filePath.toAbsolutePath());
        try {
            if (pathStr.endsWith(".json")) {
                return objectMapper.readValue(filePath.toFile(), new TypeReference<List<RepoInput>>() {});
            } else if (pathStr.endsWith(".csv")) {
                return loadFromCsv(filePath);
            } else {
                log.error("지원하지 않는 repo 목록 형식입니다 (json/csv만 지원): {}", pathStr);
                return List.of();
            }
        } catch (IOException e) {
            log.error("repo 목록 로드 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<RepoInput> loadFromCsv(Path filePath) throws IOException {
        List<RepoInput> repos = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            reader.readLine(); // header skip
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    repos.add(new RepoInput(parts[0].strip(), parts[1].strip()));
                }
            }
        }
        return repos;
    }

    // ────────────────────────────────────────────────

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    private String buildGithubUrl(String owner, String repo, String branch, String filePath) {
        return "https://github.com/" + owner + "/" + repo + "/blob/" + branch + "/" + filePath;
    }
}
