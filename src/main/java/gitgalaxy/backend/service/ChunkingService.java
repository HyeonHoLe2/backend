package gitgalaxy.backend.service;

import gitgalaxy.backend.model.ChunkDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown/RST 문서를 heading 기준으로 chunk로 분할.
 *
 * - h1~h6 heading 경계에서 분할
 * - 하나의 section이 MAX_CHUNK_CHARS를 초과하면 단락(\n\n) 기준으로 추가 분할
 * - chunk_id: {owner}_{repo}_{file_path}_{index} (특수문자 → _)
 */
@Service
@Slf4j
public class ChunkingService {

    /** Markdown heading 라인 패턴: # ~ ###### 로 시작하는 줄 */
    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /** chunk 최대 문자 수 (초과 시 단락 기준 재분할) */
    private static final int MAX_CHUNK_CHARS = 2000;

    // ────────────────────────────────────────────────

    public List<ChunkDocument> chunk(String content, String owner, String repo,
                                     String filePath, String fileUrl) {
        if (content == null || content.isBlank()) return List.of();

        List<String[]> sections = splitByHeadings(content); // [heading, body]
        List<ChunkDocument> chunks = new ArrayList<>();

        for (String[] section : sections) {
            String heading = section[0];
            String body = section[1];

            for (String part : splitIfTooLarge(body)) {
                String trimmed = part.strip();
                if (trimmed.isBlank()) continue;

                int idx = chunks.size();
                chunks.add(ChunkDocument.builder()
                        .repoOwner(owner)
                        .repoName(repo)
                        .filePath(filePath)
                        .chunkId(buildChunkId(owner, repo, filePath, idx))
                        .chunkIndex(idx)
                        .heading(heading.isBlank() ? "(intro)" : heading)
                        .content(trimmed)
                        .url(fileUrl)
                        .build());
            }
        }

        return chunks;
    }

    // ────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────

    /**
     * 문서를 heading 경계 기준으로 [heading, body] 쌍의 리스트로 분할.
     * heading 이전의 intro 내용은 heading=""로 처리.
     */
    private List<String[]> splitByHeadings(String content) {
        List<String[]> sections = new ArrayList<>();
        String[] lines = content.split("\n");

        StringBuilder body = new StringBuilder();
        String currentHeading = "";

        for (String line : lines) {
            Matcher m = HEADING_LINE.matcher(line);
            if (m.matches()) {
                // 현재까지 쌓인 내용 저장
                String accumulated = body.toString().strip();
                if (!accumulated.isBlank() || !currentHeading.isBlank()) {
                    sections.add(new String[]{currentHeading, accumulated});
                }
                currentHeading = m.group(2).strip();
                body = new StringBuilder();
            } else {
                body.append(line).append("\n");
            }
        }

        // 마지막 section
        String lastBody = body.toString().strip();
        sections.add(new String[]{currentHeading, lastBody});

        return sections.stream()
                .filter(s -> !s[1].isBlank())
                .toList();
    }

    /**
     * section body가 MAX_CHUNK_CHARS를 초과하면 빈 줄(\n\n) 기준으로 추가 분할.
     */
    private List<String> splitIfTooLarge(String text) {
        if (text.length() <= MAX_CHUNK_CHARS) return List.of(text);

        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            boolean willExceed = current.length() > 0
                    && current.length() + para.length() + 2 > MAX_CHUNK_CHARS;
            if (willExceed) {
                result.add(current.toString().strip());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(para);
        }
        if (!current.isEmpty()) result.add(current.toString().strip());

        return result.isEmpty() ? List.of(text) : result;
    }

    private String buildChunkId(String owner, String repo, String filePath, int idx) {
        return (owner + "_" + repo + "_" + filePath + "_" + idx)
                .replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_");
    }
}
