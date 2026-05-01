package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.ChunkDocument;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendingRssService {

    private static final String RSS_BASE = "https://mshibanami.github.io/GitHubTrendingRSS";

    private static final List<String> PERIODS = List.of("daily", "weekly");
    // RSS 피드 URL은 소문자 언어명 사용 (대문자 → 404)
    private static final List<String> LANGUAGES = List.of(
            "python", "javascript", "typescript", "go", "rust",
            "java", "c%2B%2B", "kotlin", "swift"
    );

    private final RepoRepository repoRepository;
    private final ChunkingService chunkingService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * RSS 피드 파싱 → repo 발굴 upsert + README 즉시 수집 → ChunkDocument 반환.
     * TrendingRssBatchJob이 반환된 청크를 임베딩 파이프라인으로 넘김.
     */
    public List<ChunkDocument> discoverAndUpsert() {
        List<String> fullNames = new ArrayList<>();

        for (String period : PERIODS) {
            for (String lang : LANGUAGES) {
                String url = RSS_BASE + "/" + period + "/" + lang + ".xml";
                try {
                    List<String> names = fetchRepoNamesFromFeed(url);
                    fullNames.addAll(names);
                    log.debug("RSS {}/{}: {}개", period, lang.isBlank() ? "all" : lang, names.size());
                } catch (Exception e) {
                    log.warn("RSS 피드 실패 {}: {}", url, e.getMessage());
                }
            }
        }

        List<ChunkDocument> allChunks = new ArrayList<>();
        int upserted = 0;

        for (String fullName : fullNames.stream().distinct().toList()) {
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2) continue;
            String owner = parts[0], name = parts[1];

            try {
                Repo repo = repoRepository.findByFullName(fullName)
                        .orElse(Repo.builder()
                                .fullName(fullName)
                                .owner(owner)
                                .name(name)
                                .createdAt(LocalDateTime.now())
                                .build());
                repo.setTracked(true);
                repoRepository.save(repo);
                upserted++;

                // README fetch → chunk
                List<ChunkDocument> chunks = fetchReadmeChunks(owner, name);
                allChunks.addAll(chunks);

            } catch (Exception e) {
                log.warn("repo upsert 실패 {}: {}", fullName, e.getMessage());
            }
        }

        log.info("TrendingRss: {}개 repo upsert, README 청크 {}개 추출", upserted, allChunks.size());
        return allChunks;
    }

    // ─── README fetch ──────────────────────────────────

    /**
     * raw.githubusercontent.com에서 README.md를 직접 다운로드 (토큰 불필요).
     * main → master 순으로 시도, 둘 다 없으면 빈 리스트 반환.
     */
    private List<ChunkDocument> fetchReadmeChunks(String owner, String name) {
        for (String branch : List.of("main", "master")) {
            String rawUrl = "https://raw.githubusercontent.com/" + owner + "/" + name
                    + "/" + branch + "/README.md";
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(rawUrl))
                        .header("User-Agent", "GitGalaxy/1.0")
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && !resp.body().isBlank()) {
                    String fileUrl = "https://github.com/" + owner + "/" + name + "#readme";
                    return chunkingService.chunk(resp.body(), owner, name, "README.md", fileUrl);
                }
            } catch (Exception e) {
                log.debug("README fetch 실패 {}/{}/{}: {}", owner, name, branch, e.getMessage());
            }
        }
        return List.of();
    }

    // ─── RSS 파싱 ───────────────────────────────────────

    private List<String> fetchRepoNamesFromFeed(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "GitGalaxy/1.0")
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(resp.body());

        List<String> names = new ArrayList<>();
        NodeList links = doc.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            String text = links.item(i).getTextContent().trim();
            if (text.startsWith("https://github.com/")) {
                String path = text.substring("https://github.com/".length());
                String[] parts = path.split("/");
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    names.add(parts[0] + "/" + parts[1]);
                }
            }
        }
        return names;
    }
}
