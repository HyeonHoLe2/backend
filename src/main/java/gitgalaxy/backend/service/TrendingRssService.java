package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.Repo;
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
    private static final List<String> LANGUAGES = List.of(
            "", "Python", "JavaScript", "TypeScript", "Go", "Rust",
            "Java", "C%2B%2B", "Kotlin", "Swift"
    );

    private final RepoRepository repoRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public int discoverAndUpsert() {
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

        int upserted = 0;
        for (String fullName : fullNames.stream().distinct().toList()) {
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2) continue;
            try {
                Repo repo = repoRepository.findByFullName(fullName)
                        .orElse(Repo.builder()
                                .fullName(fullName)
                                .owner(parts[0])
                                .name(parts[1])
                                .createdAt(LocalDateTime.now())
                                .build());
                repo.setTracked(true);
                repoRepository.save(repo);
                upserted++;
            } catch (Exception e) {
                log.warn("repo upsert 실패 {}: {}", fullName, e.getMessage());
            }
        }

        log.info("TrendingRss: {}개 후보 중 {}개 upsert", fullNames.size(), upserted);
        return upserted;
    }

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
