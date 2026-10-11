package gitgalaxy.backend.batch;

import gitgalaxy.backend.config.GithubCollectorProperties;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.metrics.CollectorMetrics;
import gitgalaxy.backend.repository.RepoRepository;
import gitgalaxy.backend.service.GithubClient;
import gitgalaxy.backend.service.RepoSaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 추적 repo 메타(스타·포크·이슈 수·설명 등) 주기 갱신.
 * 가장 오래전에 수집된 repo부터 N개씩 순환 → 전체 repo가 돌아가며 갱신됨.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepoMetaRefreshJob {

    private final RepoRepository repoRepository;
    private final GithubClient githubClient;
    private final RepoSaveService repoSaveService;
    private final GithubCollectorProperties props;
    private final CollectorMetrics collectorMetrics;

    @Scheduled(cron = "${github.collector.meta-refresh-cron:0 40 * * * *}")
    @SchedulerLock(name = "repoMetaRefresh", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void run() {
        List<Repo> repos = repoRepository.findStalestTracked(PageRequest.of(0, props.getMetaRefreshBatchSize()));
        log.info("RepoMetaRefreshJob 시작: {}개", repos.size());

        int updated = 0, untracked = 0, failed = 0, moved = 0;
        for (Repo repo : repos) {
            if (!githubClient.hasBudget()) {
                log.warn("GitHub rate limit 여유분 도달 (남은 요청 {}) → 메타 갱신 중단", githubClient.getRateLimitRemaining());
                break;
            }
            try {
                GithubClient.RepoMetaWithName r = githubClient.getRepoMetaFollowingMoves(repo.getOwner(), repo.getName());
                if (r.fullName().equalsIgnoreCase(repo.getFullName())) {
                    repoSaveService.saveOrUpdate(repo.getOwner(), repo.getName(), r.meta());
                    updated++;
                    collectorMetrics.repoMetaRefresh("updated");
                } else {
                    String oldName = repo.getFullName(); // applyMove가 같은 엔티티 이름을 바꾸므로 먼저 보관
                    String result = repoSaveService.applyMove(repo, r.fullName(), r.meta());
                    moved++;
                    collectorMetrics.repoMetaRefresh(result);
                    log.info("repo 이동 {} → {} ({})", oldName, r.fullName(), result);
                }
            } catch (GithubClient.GithubApiException e) {
                if (e.isNotFound()) {
                    repoSaveService.untrack(repo);
                    untracked++;
                    collectorMetrics.repoMetaRefresh("untracked");
                } else {
                    failed++;
                    collectorMetrics.repoMetaRefresh("failed");
                    log.warn("메타 갱신 실패 {}: {}", repo.getFullName(), e.getMessage());
                }
            } catch (Exception e) {
                failed++;
                collectorMetrics.repoMetaRefresh("failed");
                log.warn("메타 갱신 실패 {}: {}", repo.getFullName(), e.getMessage());
            }
        }

        log.info("RepoMetaRefreshJob 완료: 갱신 {}, 이름 변경·이전 {}, 추적 해제(삭제/비공개) {}, 실패 {}",
                updated, moved, untracked, failed);
    }
}
