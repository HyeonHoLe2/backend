package gitgalaxy.backend.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import gitgalaxy.backend.config.GeminiProperties;
import gitgalaxy.backend.config.GithubCollectorProperties;
import gitgalaxy.backend.entity.RepoAiSummary;
import gitgalaxy.backend.repository.RepoAiSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiSummerize {

    private static final long RETRY_DELAY_CAP_MS = 60_000;

    private final GithubCollectorProperties props;
    private final GeminiProperties geminiProperties;
    private final RepoAiSummaryRepository repoAiSummaryRepository;

    public void summarize(String owner, String repo) {
        if (geminiProperties.getApiKey() == null || geminiProperties.getApiKey().isBlank()) {
            log.warn("[{}/{}] gemini.api-key / GEMINI_API_KEY 없음 — 요약 생략", owner, repo);
            return ;
        }

        Path jsonlFile = repoDir(owner, repo).resolve("intro.jsonl");
        if (!Files.isRegularFile(jsonlFile)) {
            log.warn("[{}/{}] intro.jsonl 없음 — 요약 생략", owner, repo);
            return ;
        }

        try {
            String content = Files.readString(jsonlFile, StandardCharsets.UTF_8);
            Client client = Client.builder()
                    .apiKey(geminiProperties.getApiKey())
                    .build();

            String prompt =
                    "You are summarizing a GitHub repository for a short 'About' section.\n\n" +

                            "Summarize the project in exactly 2 sentences.\n\n" +

                            "Rules:\n" +
                            "- Sentence 1: Clearly state what the project is and what it does (e.g., framework, library, tool, platform).\n" +
                            "- Sentence 2: Describe its key concepts, architecture, or defining features.\n" +

                            "- Focus on the overall purpose of the repository, not a specific file, example, or feature.\n" +
                            "- If the repository contains multiple features, summarize the main theme rather than listing all features.\n" +

                            "- Use specific technical terms only if they are essential to understanding the project.\n" +
                            "- Avoid overly detailed or low-level technical descriptions (e.g., internal parameters, exact metrics, implementation details).\n" +

                            "- Do NOT guess or use vague phrases like 'likely' or 'appears to'.\n" +
                            "- Do NOT include specific numbers, metrics, or claims that may change (e.g., '1000+ integrations').\n" +
                            "- Do NOT describe it as a generic 'collection of modules' or 'set of APIs'.\n" +

                            "- Ignore development processes (testing, linting, commit rules, etc.).\n" +
                            "- Base your answer primarily on README content if available.\n" +
                            "- Keep it concise, clear, and readable for developers.\n\n" +

                            content;
            String model = geminiProperties.getModel();
            int maxAttempts = Math.max(1, geminiProperties.getMaxRetries());
            long waitMs = Math.max(100L, geminiProperties.getRetryDelayMs());
            String str = " ";
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    GenerateContentResponse response = client.models.generateContent(
                            model,
                            prompt,
                            null);
                    log.info("[{}/{}] Gemini 요약:\n{}", owner, repo, response.text());
                    str = response.text();
                    if (str != null && !str.isBlank()) {
                        upsertSummary(owner, repo, str);
                    } else {
                        log.warn("[{}/{}] Gemini 요약이 비어있음 — DB 저장 생략", owner, repo);
                    }
                    return;
                } catch (Exception e) {
                    if (!isRetryable(e) || attempt == maxAttempts) {
                        log.error("[{}/{}] Gemini 요약 실패 (시도 {}/{})", owner, repo, attempt, maxAttempts, e);
                        return ;
                    }
                    log.warn("[{}/{}] Gemini 일시 오류 — {}ms 후 재시도 ({}/{}) — {}",
                            owner, repo, waitMs, attempt, maxAttempts, e.getMessage());
                    sleepQuietly(waitMs);
                    waitMs = Math.min(waitMs * 2, RETRY_DELAY_CAP_MS);
                }
            }
        } catch (Exception e) {
            log.error("[{}/{}] Gemini 요약 준비 단계 실패", owner, repo, e);

        }
        return ;
    }

    private void upsertSummary(String owner, String repo, String summary) {
        RepoAiSummary entity = repoAiSummaryRepository
                .findByOwnerAndRepo(owner, repo)
                .orElseGet(RepoAiSummary::new);

        entity.setOwner(owner);
        entity.setRepo(repo);
        entity.setSummary(summary);
        repoAiSummaryRepository.save(entity);
        log.info("[{}/{}] Gemini 요약 DB 저장 완료", owner, repo);
    }

    private static boolean isRetryable(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) {
                continue;
            }
            String u = m.toUpperCase();
            if (u.contains("503")
                    || u.contains("429")
                    || u.contains("529")
                    || u.contains("UNAVAILABLE")
                    || u.contains("RESOURCE_EXHAUSTED")
                    || u.contains("HIGH DEMAND")
                    || u.contains("RATE LIMIT")
                    || u.contains("TOO MANY REQUESTS")) {
                return true;
            }
        }
        return false;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Path repoDir(String owner, String repo) {
        return Path.of(props.getOutputDir(), owner, repo);
    }
}
