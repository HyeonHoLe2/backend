package gitgalaxy.backend.service;

import tools.jackson.databind.ObjectMapper;
import gitgalaxy.backend.config.GithubCollectorProperties;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.model.RepoResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JSONL 저장 / batch_summary.json·csv 작성 / skip 판단 담당.
 *
 * 출력 구조:
 *   {outputDir}/{owner}/{repo}/chunks.jsonl
 *   {outputDir}/batch_summary.json
 *   {outputDir}/batch_summary.csv
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private final GithubCollectorProperties props;
    private final ObjectMapper objectMapper;

    // ────────────────────────────────────────────────
    // Skip 판단
    // ────────────────────────────────────────────────

    /**
     * 이미 수집된 repo인지 확인 (chunks.jsonl 존재 + 비어있지 않으면 수집 완료로 간주)
     */
    public boolean isAlreadyCollected(String owner, String repo) {
        Path jsonlFile = repoDir(owner, repo).resolve("chunks.jsonl");
        return Files.exists(jsonlFile) && jsonlFile.toFile().length() > 0;
    }

    // ────────────────────────────────────────────────
    // JSONL 저장
    // ────────────────────────────────────────────────

    public void saveChunks(String owner, String repo, List<ChunkDocument> chunks) throws IOException {
        Path dir = repoDir(owner, repo);
        Files.createDirectories(dir);

        Path jsonlFile = dir.resolve("chunks.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(jsonlFile, StandardCharsets.UTF_8)) {
            for (ChunkDocument chunk : chunks) {
                writer.write(objectMapper.writeValueAsString(chunk));
                writer.newLine();
            }
        }
        log.info("[{}] chunks.jsonl 저장 완료: {} 청크", owner + "/" + repo, chunks.size());
    }

    // ────────────────────────────────────────────────
    // Batch 요약 저장
    // ────────────────────────────────────────────────

    public void saveBatchSummary(List<RepoResult> results) {
        Path outDir = Path.of(props.getOutputDir());
        try {
            Files.createDirectories(outDir);
            writeJson(outDir.resolve("batch_summary.json"), results);
            writeCsv(outDir.resolve("batch_summary.csv"), results);
            log.info("batch_summary 저장 완료 → {}", outDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("batch_summary 저장 실패", e);
        }
    }

    // ────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────

    private void writeJson(Path file, List<RepoResult> results) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), results);
    }

    private void writeCsv(Path file, List<RepoResult> results) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("owner,repo,status,file_count,chunk_count,duration_ms,error_message,processed_at");
            w.newLine();
            for (RepoResult r : results) {
                w.write(String.join(",",
                        csv(r.getOwner()),
                        csv(r.getRepo()),
                        csv(r.getStatus()),
                        String.valueOf(r.getFileCount()),
                        String.valueOf(r.getChunkCount()),
                        String.valueOf(r.getDurationMs()),
                        csv(r.getErrorMessage()),
                        csv(r.getProcessedAt() != null ? r.getProcessedAt().toString() : "")
                ));
                w.newLine();
            }
        }
    }

    /** null 안전 + 쉼표/줄바꿈 포함 시 큰따옴표로 감싸기 */
    private String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Path repoDir(String owner, String repo) {
        return Path.of(props.getOutputDir(), owner, repo);
    }
}
