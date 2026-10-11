package gitgalaxy.backend.controller;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.entity.RepoHourlyMetrics;

import gitgalaxy.backend.model.DailyTrendingResponse;
import gitgalaxy.backend.model.RepoListItemDto;
import gitgalaxy.backend.model.RepoResponse;
import gitgalaxy.backend.model.TimelinePointDto;

import gitgalaxy.backend.repository.RepoHourlyMetricsRepository;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.repository.TrendingRepository;

import gitgalaxy.backend.service.RagService;
import gitgalaxy.backend.service.RepoService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/repos")
@RequiredArgsConstructor
public class RepoController {


    private final RepoRepository repoRepository;
    private final RepoHourlyMetricsRepository metricsRepository;
    private final RagService ragService;
    private final RepoService repoService;
    private final TrendingRepository trendingRepository;
    private final gitgalaxy.backend.service.GalaxyService galaxyService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    @GetMapping
    public List<RepoListItemDto> list() {
        Map<String, RepoHourlyMetrics> latestMap = metricsRepository.findLatestPerRepo()
                .stream()
                .collect(Collectors.toMap(
                        m -> m.getRepoOwner() + "/" + m.getRepoName(),
                        m -> m
                ));
        return repoRepository.findByTrackedTrue()
                .stream()
                .map(repo -> RepoListItemDto.of(repo, latestMap.get(repo.getFullName())))
                .sorted(Comparator.comparingDouble(RepoListItemDto::brightnessScore).reversed())
                .toList();
    }

    @GetMapping("/trending")
    public List<RepoListItemDto> trending(@RequestParam(defaultValue = "50") int limit) {
        List<RepoHourlyMetrics> topMetrics = metricsRepository.findTrending(limit);

        Map<String, Repo> repoMap = repoRepository.findByFullNameIn(topMetrics.stream()
                        .map(m -> m.getRepoOwner() + "/" + m.getRepoName())
                        .toList())
                .stream()
                .collect(Collectors.toMap(Repo::getFullName, r -> r, (a, b) -> a));

        return topMetrics.stream()
                .map(m -> {
                    Repo repo = repoMap.get(m.getRepoOwner() + "/" + m.getRepoName());
                    if (repo == null) {
                        repo = Repo.builder()
                                .fullName(m.getRepoOwner() + "/" + m.getRepoName())
                                .owner(m.getRepoOwner())
                                .name(m.getRepoName())
                                .build();
                    }
                    return RepoListItemDto.of(repo, m);
                })
                .toList();
    }

    /**
     * 일간 트렌딩: UTC 하루 동안 GitHub 스타 증가량(S1 − S0) 순위.
     * date 미지정 시 가장 최근 계산된 날(진행 중인 오늘이면 "지금까지" 순위).
     */
    /**
     * 메인 은하 화면: 오늘 트렌딩(크기=스타 증가, 밝기=활동량) + 최근 N일 트렌딩 + 배경 repo + 별자리 연결.
     */
    @GetMapping("/galaxy")
    public ResponseEntity<gitgalaxy.backend.model.GalaxyResponse> galaxy(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "7") int days) {
        var res = galaxyService.get(date, Math.max(1, Math.min(days, 7)));
        return res == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(res);
    }

    @GetMapping("/trending/daily")
    public ResponseEntity<DailyTrendingResponse> dailyTrending(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "25") int limit) {
        LocalDate day = date != null ? date : trendingRepository.findLatestTrendDate();
        if (day == null) return ResponseEntity.noContent().build();

        List<TrendingRepository.TrendingWithActivity> rows = trendingRepository.findTrendingWithActivity(day, Math.min(limit, 100));
        Map<String, Repo> repoMap = repoRepository.findByFullNameIn(rows.stream().map(r -> r.row().fullName()).toList())
                .stream().collect(Collectors.toMap(Repo::getFullName, r -> r, (a, b) -> a));
        Map<String, TrendingRepository.EvidenceRow> evidence = trendingRepository.findEvidence(day);
        List<DailyTrendingResponse.Item> items = rows.stream().map(t -> {
            TrendingRepository.TrendingRow r = t.row();
            TrendingRepository.Activity a = t.activity();
            Repo repo = repoMap.get(r.fullName());
            return new DailyTrendingResponse.Item(r.rank(), r.fullName(), r.starsGained(), r.stargazers(), r.method(),
                    repo != null ? repo.getDescription() : null, repo != null ? repo.getLanguage() : null,
                    a == null ? null : a.brightness(),
                    a == null ? null : new DailyTrendingResponse.Activity(a.pushes(), a.prsOpened(), a.prsMerged(),
                            a.issuesOpened(), a.issuesClosed(), a.releases(), a.forks(), a.score(), a.hours()),
                    ev(evidence, r.fullName(), TrendingRepository.EvidenceRow::summary),
                    ev(evidence, r.fullName(), TrendingRepository.EvidenceRow::trigger),
                    ev(evidence, r.fullName(), TrendingRepository.EvidenceRow::strength),
                    ev(evidence, r.fullName(), TrendingRepository.EvidenceRow::appeal),
                    evidence.containsKey(r.fullName()) ? parseEvidence(evidence.get(r.fullName()).evidenceJson()) : null);
        }).toList();
        boolean complete = day.isBefore(LocalDate.now(ZoneOffset.UTC));
        return ResponseEntity.ok(new DailyTrendingResponse(day, complete, items));
    }

    private static String ev(Map<String, TrendingRepository.EvidenceRow> m, String repo,
                             java.util.function.Function<TrendingRepository.EvidenceRow, String> f) {
        TrendingRepository.EvidenceRow e = m.get(repo);
        return e == null ? null : f.apply(e);
    }

    private gitgalaxy.backend.model.TrendEvidence parseEvidence(String json) {
        try {
            return objectMapper.readValue(json, gitgalaxy.backend.model.TrendEvidence.class);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<RepoListItemDto> getRepo(
            @PathVariable String owner,
            @PathVariable String repo) {
        return repoRepository.findByFullName(owner + "/" + repo)
                .map(r -> {
                    RepoHourlyMetrics latest = metricsRepository
                            .findTopByRepoOwnerAndRepoNameOrderByBucketDesc(owner, repo)
                            .orElse(null);
                    return ResponseEntity.ok(RepoListItemDto.of(r, latest));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{owner}/{repo}/timeline")
    public List<TimelinePointDto> timeline(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(defaultValue = "24") int hours) {
        LocalDateTime to   = LocalDateTime.now();
        LocalDateTime from = to.minusHours(hours);
        return metricsRepository
                .findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(owner, repo, from, to)
                .stream()
                .map(TimelinePointDto::from)
                .toList();
    }

    @GetMapping("/{owner}/{repo}/explain")
    public Map<String, String> explain(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam String q) {
        String answer = ragService.explain(owner, repo, q);
        return Map.of("repo", owner + "/" + repo, "question", q, "answer", answer);
    }

    @GetMapping("/rag/search")
    public Map<String, String> rag_search(@RequestParam String q) {
        String answer = ragService.explainGlobal(q);
        return Map.of("question", q, "answer", answer);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception e) {
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
    }


    /** 트렌드 이유: 이 repo가 트렌딩에 오른 가장 최근 날의 근거 (하루 확정 때 생성·저장된 값, 요청 시 LLM 호출 없음) */
    @GetMapping("/{owner}/{repo}/trend")
    public ResponseEntity<Map<String, Object>> trend(
            @PathVariable String owner,
            @PathVariable String repo) {
        TrendingRepository.LatestEvidence latest = trendingRepository.findLatestEvidence(owner + "/" + repo);
        if (latest == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("owner", owner, "repo", repo, "error", "트렌딩 근거 없음"));
        }
        TrendingRepository.EvidenceRow e = latest.evidence();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("owner", owner);
        body.put("repo", repo);
        body.put("trendDate", latest.trendDate().toString());
        body.put("rank", latest.rank());
        body.put("starsGained", latest.starsGained());
        body.put("strength", e.strength());
        body.put("trigger", e.trigger());
        body.put("appeal", e.appeal());
        body.put("summary", e.summary());
        body.put("evidence", parseEvidence(e.evidenceJson()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/search")
    public ResponseEntity<List<RepoResponse>> search(@RequestParam String q) {
        return ResponseEntity.ok(repoService.search(q));
    }


}
