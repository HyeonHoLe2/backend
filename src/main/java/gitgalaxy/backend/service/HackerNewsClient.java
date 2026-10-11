package gitgalaxy.backend.service;

import gitgalaxy.backend.model.TrendEvidence.HnPost;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Hacker News 검색 (Algolia 공개 API, 인증 불필요): repo URL이 링크된 게시글 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HackerNewsClient {

    private static final String SEARCH = "https://hn.algolia.com/api/v1/search_by_date";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 게시글 상위 댓글 앞부분 (HN 화면 순서 = 순위 순). HTML 태그 제거 */
    public List<String> fetchTopComments(String hnUrl, int limit, int maxChars) {
        String id = hnUrl.substring(hnUrl.indexOf("id=") + 3);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://hn.algolia.com/api/v1/items/" + id))
                    .header("User-Agent", "GitGalaxy/1.0").timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> res = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(30, TimeUnit.SECONDS);
            if (res.statusCode() != 200) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode c : objectMapper.readTree(res.body()).path("children")) {
                String text = org.springframework.web.util.HtmlUtils.htmlUnescape(
                        c.path("text").asText("").replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").strip();
                if (text.isBlank()) continue;
                out.add(text.length() > maxChars ? text.substring(0, maxChars) + "…" : text);
                if (out.size() >= limit) break;
            }
            return out;
        } catch (Exception e) {
            log.debug("HN 댓글 조회 오류 {}: {}", hnUrl, e.getMessage());
            return List.of();
        }
    }

    /** [from, to) 사이에 올라온, github.com/{fullName} 을 링크한 게시글 (점수 높은 순) */
    public List<HnPost> findPosts(String fullName, Instant from, Instant to) {
        String url = SEARCH + "?restrictSearchableAttributes=url&hitsPerPage=20&tags=story"
                + "&numericFilters=" + URLEncoder.encode("created_at_i>=" + from.getEpochSecond()
                        + ",created_at_i<" + to.getEpochSecond(), StandardCharsets.UTF_8)
                + "&query=" + URLEncoder.encode("github.com/" + fullName, StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "GitGalaxy/1.0").timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> res = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(30, TimeUnit.SECONDS);
            if (res.statusCode() != 200) {
                log.debug("HN 검색 실패 {}: HTTP {}", fullName, res.statusCode());
                return List.of();
            }
            List<HnPost> posts = new ArrayList<>();
            String needle = ("github.com/" + fullName).toLowerCase();
            for (JsonNode h : objectMapper.readTree(res.body()).path("hits")) {
                // 검색은 부분 일치라 다른 repo(같은 owner의 다른 이름 등)가 섞일 수 있어 URL로 한 번 더 확인
                String link = h.path("url").asText("").toLowerCase();
                if (!(link.endsWith(needle) || link.contains(needle + "/") || link.contains(needle + "?") || link.contains(needle + "#"))) continue;
                posts.add(new HnPost(h.path("title").asText(), h.path("points").asInt(), h.path("num_comments").asInt(),
                        h.path("created_at").asText(), "https://news.ycombinator.com/item?id=" + h.path("objectID").asText(), List.of()));
            }
            posts.sort(Comparator.comparingInt(HnPost::points).reversed());
            return posts.size() > 3 ? posts.subList(0, 3) : posts;
        } catch (Exception e) {
            log.debug("HN 검색 오류 {}: {}", fullName, e.getMessage());
            return List.of();
        }
    }
}
