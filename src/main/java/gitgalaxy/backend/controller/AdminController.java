package gitgalaxy.backend.controller;

import gitgalaxy.backend.model.RepoInput;
import gitgalaxy.backend.model.RepoResult;
import gitgalaxy.backend.service.RepoCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 운영/개발용 관리 API.
 *
 * POST /admin/collect          → repos.json 기준 전체 수집 수동 트리거
 * POST /admin/collect/{owner}/{repo} → 단일 repo 즉시 수집
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final RepoCollectorService repoCollectorService;

    @PostMapping("/collect")
    public Map<String, Object> collectAll() {
        log.info("수동 전체 수집 트리거");
        List<RepoResult> results = repoCollectorService.runBatch();

        long success = results.stream().filter(r -> "success".equals(r.getStatus())).count();
        long failed  = results.stream().filter(r -> "failed".equals(r.getStatus())).count();
        long skipped = results.stream().filter(r -> "skipped".equals(r.getStatus())).count();

        return Map.of(
                "total",   results.size(),
                "success", success,
                "failed",  failed,
                "skipped", skipped
        );
    }

    @PostMapping("/collect/{owner}/{repo}")
    public RepoResult collectOne(
            @PathVariable String owner,
            @PathVariable String repo) {
        log.info("수동 단일 수집 트리거: {}/{}", owner, repo);
        return repoCollectorService.collectRepo(new RepoInput(owner, repo));
    }
}
