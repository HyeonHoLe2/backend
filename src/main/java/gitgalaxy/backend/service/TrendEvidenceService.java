package gitgalaxy.backend.service;

import gitgalaxy.backend.config.TrendingProperties;
import gitgalaxy.backend.model.TrendEvidence;
import gitgalaxy.backend.repository.TrendingRepository;
import gitgalaxy.backend.repository.TrendingRepository.TrendingRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 일간 트렌딩 repo가 뜬 시기의 근거(릴리즈·Hacker News·생성일·소유자)를 모으고,
 * 근거에 있는 사실만으로 요약. 실제 유입 경로는 repo 관리자만 볼 수 있어 원인을 단정하지 않음.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrendEvidenceService {

    /** 이 점수 미만 HN 글은 화제가 아니었던 것으로 보고 근거에서 제외 */
    private static final int MIN_HN_POINTS = 10;

    private final TrendingProperties props;
    private final TrendingRepository trendingRepository;
    private final GithubClient githubClient;
    private final HackerNewsClient hackerNewsClient;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public int generate(LocalDate day) {
        List<TrendingRow> top = trendingRepository.findTrending(day, props.getFeaturedTop());
        if (top.isEmpty()) return 0;

        Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
        Map<String, JsonNode> meta = fetchMeta(top.stream().map(TrendingRow::fullName).toList());

        // 같은 owner의 repo가 여러 개 순위에 오르면 함께 뜬 것으로 표시
        Map<String, List<String>> byOwner = new HashMap<>();
        top.forEach(r -> byOwner.computeIfAbsent(r.fullName().split("/")[0], k -> new ArrayList<>()).add(r.fullName()));

        // 끌린 점 재사용: 이전에 만든 appeal이 있고 순위가 급상승하지 않았으면 LLM을 다시 부르지 않음
        Map<String, String> prevAppeals = trendingRepository.findLatestAppeals(
                top.stream().map(TrendingRow::fullName).toList(), day);
        Map<String, Integer> prevRanks = new HashMap<>();
        trendingRepository.findTrending(day.minusDays(1), props.getTopN())
                .forEach(r -> prevRanks.put(r.fullName(), r.rank()));
        int reused = 0, regenerated = 0, created = 0;

        int withEvidence = 0;
        for (TrendingRow row : top) {
            JsonNode repo = meta.get(row.fullName());
            if (repo == null) continue; // 삭제·비공개·이름 변경
            TrendEvidence ev = buildEvidence(row.fullName(), repo, dayStart, dayEnd);
            if (!ev.isEmpty()) withEvidence++;

            Trigger trigger = decideTrigger(row, ev, day, byOwner.get(row.fullName().split("/")[0]));
            String appeal = null;
            if (props.isReasonEnabled()) {
                String prev = prevAppeals.get(row.fullName());
                int prevRank = prevRanks.getOrDefault(row.fullName(), props.getTopN() + 1);
                boolean surged = prevRank - row.rank() >= props.getAppealRegenRankJump();
                if (prev != null && !surged) {
                    appeal = prev;
                    reused++;
                } else {
                    appeal = summarizeAppeal(ev);
                    if (prev == null) created++;
                    else {
                        regenerated++;
                        log.info("순위 급상승 {}: {}위 → {}위 → 끌린 점 재생성", row.fullName(),
                                prevRank > props.getTopN() ? "순위권 밖" : prevRank, row.rank());
                    }
                }
            }
            String summary = "계기(추정, 근거 " + trigger.strengthKo() + "): " + trigger.text()
                    + (appeal != null ? " / 끌린 점: " + appeal : "");
            try {
                trendingRepository.upsertEvidence(day, row.fullName(), new TrendingRepository.EvidenceRow(
                        objectMapper.writeValueAsString(ev), summary, trigger.text(), trigger.strength(), appeal));
            } catch (Exception e) {
                log.warn("근거 저장 실패 {}: {}", row.fullName(), e.getMessage());
            }
        }
        log.info("트렌딩 근거 {}: {}개 중 근거 있는 repo {}개, 끌린 점 재사용 {} / 급상승 재생성 {} / 신규 생성 {} (LLM {}회)",
                day, top.size(), withEvidence, reused, regenerated, created, regenerated + created);
        return top.size();
    }

    // ─── 계기 (규칙 기반 추정, LLM 미사용) ─────────────────────

    record Trigger(String text, String strength) {
        String strengthKo() {
            return switch (strength) { case "strong" -> "강함"; case "medium" -> "중간"; case "weak" -> "약함"; default -> "없음"; };
        }
    }

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * 강함: 3일 안에 HN 100점 이상 / 중간: 2일 안에 릴리즈 또는 HN 30~99점 / 약함: 30일 안에 새로 만든 레포 / 없음.
     * 여러 근거가 있으면 강한 것부터 이어 붙이고, 시간대별 스냅샷으로 릴리즈 전후 증가 속도를 덧붙임.
     */
    private Trigger decideTrigger(TrendingRow row, TrendEvidence ev, LocalDate day, List<String> sameOwner) {
        List<String> parts = new ArrayList<>();
        String strength = "none";

        TrendEvidence.HnPost hn = ev.hnPosts().isEmpty() ? null : ev.hnPosts().get(0);
        if (hn != null && hn.points() >= 30) {
            parts.add(utc(hn.createdAt(), MD) + " Hacker News " + (hn.points() >= 100 ? "1면 화제" : "게시")
                    + " (" + hn.points() + "점, 댓글 " + hn.comments() + "개) 이후 유입");
            strength = hn.points() >= 100 ? "strong" : "medium";
        }
        if (ev.recentRelease() != null) {
            String rate = releaseRateChange(row.fullName(), day, ev.recentRelease().publishedAt());
            parts.add(utc(ev.recentRelease().publishedAt(), MD_HM) + " UTC " + ev.recentRelease().tagName() + " 릴리즈"
                    + (rate != null ? " (" + rate + ")" : ""));
            if (strength.equals("none")) strength = "medium";
        }
        if (ev.newRepo()) {
            parts.add(utc(ev.createdAt(), MD) + "에 새로 공개된 레포");
            if (strength.equals("none")) strength = "weak";
        }
        if (sameOwner != null && sameOwner.size() > 1) {
            parts.add("같은 소유자의 레포 " + (sameOwner.size() - 1) + "개도 함께 순위에 오름");
        }
        if (strength.equals("none")) {
            parts.add(0, "확인된 계기 없음 (Hacker News·릴리즈·신규 공개 해당 없음, 외부 유입 경로는 확인 불가)");
        }
        return new Trigger(String.join(", ", parts), strength);
    }

    /** 릴리즈 시각 전후 시간당 스타 증가 속도 비교 (스냅샷이 전후로 하나 이상씩 있을 때만) */
    private String releaseRateChange(String fullName, LocalDate day, String publishedAt) {
        LocalDateTime release = LocalDateTime.ofInstant(Instant.parse(publishedAt), ZoneOffset.UTC);
        List<Object[]> snaps = trendingRepository.findSnapshotsOfDay(fullName, day);
        Object[] before = null, at = null, after = null;
        for (Object[] s : snaps) {
            LocalDateTime t = (LocalDateTime) s[0];
            if (!t.isAfter(release)) { if (at != null) before = at; at = s; }
            else { after = s; }
        }
        if (at == null || after == null) return null;
        double hoursAfter = Duration.between((LocalDateTime) at[0], (LocalDateTime) after[0]).toMinutes() / 60.0;
        double rateAfter = ((int) after[1] - (int) at[1]) / Math.max(hoursAfter, 1);
        if (before == null) return String.format("릴리즈 후 시간당 +%.0f", rateAfter);
        double hoursBefore = Duration.between((LocalDateTime) before[0], (LocalDateTime) at[0]).toMinutes() / 60.0;
        double rateBefore = ((int) at[1] - (int) before[1]) / Math.max(hoursBefore, 1);
        return String.format("릴리즈 전 시간당 +%.0f → 후 +%.0f", rateBefore, rateAfter);
    }

    private static String utc(String iso, DateTimeFormatter f) {
        return LocalDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC).format(f);
    }

    private TrendEvidence buildEvidence(String fullName, JsonNode repo, Instant dayStart, Instant dayEnd) {
        String createdAt = repo.path("createdAt").asText(null);
        boolean newRepo = createdAt != null && Instant.parse(createdAt).isAfter(dayStart.minus(30, ChronoUnit.DAYS));

        JsonNode owner = repo.path("owner");
        Integer followers = owner.has("followers") ? owner.path("followers").path("totalCount").asInt() : null;

        TrendEvidence.Release release = null;
        for (JsonNode r : repo.path("releases").path("nodes")) {
            String publishedAt = r.path("publishedAt").asText(null);
            if (publishedAt == null) continue;
            Instant t = Instant.parse(publishedAt);
            if (!t.isBefore(dayStart.minus(2, ChronoUnit.DAYS)) && t.isBefore(dayEnd)) {
                release = new TrendEvidence.Release(r.path("tagName").asText(), r.path("name").asText(null), publishedAt,
                        excerpt(r.path("description").asText(null), 1500));
                break; // 최신순 → 구간 안의 가장 최근 릴리즈
            }
        }

        List<TrendEvidence.HnPost> hn = hackerNewsClient.findPosts(fullName, dayStart.minus(3, ChronoUnit.DAYS), dayEnd)
                .stream().filter(p -> p.points() >= MIN_HN_POINTS).toList();
        // 가장 점수 높은 글만 상위 댓글을 붙임 (사람들이 무엇에 반응했는지)
        if (!hn.isEmpty()) {
            List<TrendEvidence.HnPost> withComments = new ArrayList<>(hn);
            TrendEvidence.HnPost top = hn.get(0);
            withComments.set(0, new TrendEvidence.HnPost(top.title(), top.points(), top.comments(), top.createdAt(),
                    top.hnUrl(), hackerNewsClient.fetchTopComments(top.hnUrl(), 5, 280)));
            hn = withComments;
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode t : repo.path("repositoryTopics").path("nodes")) topics.add(t.path("topic").path("name").asText());
        return new TrendEvidence(excerpt(repo.path("description").asText(null), 400), topics,
                repo.path("primaryLanguage").path("name").asText(null),
                createdAt, newRepo, owner.path("__typename").asText(null), followers, release, hn);
    }

    private static String excerpt(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 끌린 점: 이 레포가 사람들에게 주는 것 + (HN 댓글이 있으면) 반응 하나. 근거 내용만 사용 */
    private String summarizeAppeal(TrendEvidence ev) {
        List<String> facts = new ArrayList<>();
        if (ev.description() != null) facts.add("레포 설명(About): " + ev.description());
        if (!ev.topics().isEmpty()) facts.add("토픽: " + String.join(", ", ev.topics()));
        if (ev.language() != null) facts.add("주 언어: " + ev.language());
        if (ev.recentRelease() != null && ev.recentRelease().notes() != null) {
            facts.add("최근 릴리즈 " + ev.recentRelease().tagName() + " 노트:\n  " + ev.recentRelease().notes().replace("\n", "\n  "));
        }
        for (TrendEvidence.HnPost p : ev.hnPosts()) {
            facts.add("Hacker News 게시글 제목: \"" + p.title() + "\""
                    + (p.topComments().isEmpty() ? "" : "\n  상위 댓글:\n  · " + String.join("\n  · ", p.topComments())));
        }
        if (facts.isEmpty()) return null;

        String prompt = """
                아래는 GitHub 레포에 대해 확인된 내용입니다.

                %s

                이 레포에 사람들이 끌린 점을 한국어 1~2문장, 60~120자 정도로 써주세요.
                - 첫 문장: 이 레포가 사용자에게 주는 것 (무엇을 할 수 있게 해주는지)을 구체적으로. 예: "포토샵을 대체하는 무료 오픈소스 이미지 편집기"
                - Hacker News 댓글이 있으면 둘째 문장에 댓글에서 나온 반응(질문·우려·기대) 하나를 구체적으로.
                - 위 내용에 없는 기능·사실·원인을 지어내지 마세요. "혁신적인", "주목받는" 같은 막연한 수식어는 쓰지 마세요.
                - 레포 이름은 쓰지 말고, 존댓말로, 줄바꿈 없이 요약 문장만 출력하세요.
                """.formatted("- " + String.join("\n- ", facts));
        try {
            String text = llmService.chat(prompt);
            return text == null || text.isBlank() ? null : text.replaceAll("\\s*\\n+\\s*", " ").strip();
        } catch (Exception e) {
            log.warn("끌린 점 요약 실패: {}", e.getMessage());
            return null;
        }
    }

    /** GraphQL 1회(최대 100개 묶음)로 생성일·소유자·최근 릴리즈 조회 */
    private Map<String, JsonNode> fetchMeta(List<String> fullNames) {
        Map<String, JsonNode> result = new HashMap<>();
        StringBuilder q = new StringBuilder("query {");
        for (int i = 0; i < fullNames.size(); i++) {
            String[] p = fullNames.get(i).split("/", 2);
            q.append(" r").append(i).append(": repository(owner: \"").append(p[0]).append("\", name: \"").append(p[1]).append("\") {")
             .append(" description primaryLanguage { name } repositoryTopics(first: 8) { nodes { topic { name } } }")
             .append(" createdAt owner { __typename login ... on User { followers { totalCount } } }")
             .append(" releases(first: 5, orderBy: {field: CREATED_AT, direction: DESC}) { nodes { tagName name publishedAt description } } }");
        }
        JsonNode data = githubClient.graphql(q.append(" }").toString());
        for (int i = 0; i < fullNames.size(); i++) {
            JsonNode repo = data.path("r" + i);
            if (!repo.isMissingNode() && !repo.isNull()) result.put(fullNames.get(i), repo);
        }
        return result;
    }
}
