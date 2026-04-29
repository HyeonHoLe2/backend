package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.RepoHourlyMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface RepoHourlyMetricsRepository extends JpaRepository<RepoHourlyMetrics, Long> {

    /** hour_bucket 기준 upsert (ON CONFLICT: 기존 행 덮어쓰기) */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO repo_hourly_metrics
                (repo_owner, repo_name, hour_bucket, watch_count, push_count, pr_count, issue_count, created_at)
            VALUES (:owner, :name, :hourBucket, :watch, :push, :pr, :issue, NOW())
            ON CONFLICT (repo_owner, repo_name, hour_bucket) DO UPDATE SET
                watch_count = EXCLUDED.watch_count,
                push_count  = EXCLUDED.push_count,
                pr_count    = EXCLUDED.pr_count,
                issue_count = EXCLUDED.issue_count
            """, nativeQuery = true)
    void upsert(@Param("owner") String owner,
                @Param("name") String name,
                @Param("hourBucket") LocalDateTime hourBucket,
                @Param("watch") int watch,
                @Param("push") int push,
                @Param("pr") int pr,
                @Param("issue") int issue);

    /** 특정 repo의 기간 내 시간별 메트릭 조회 (score 계산, 차트용) */
    List<RepoHourlyMetrics> findByRepoOwnerAndRepoNameAndHourBucketBetweenOrderByHourBucketAsc(
            String repoOwner, String repoName, LocalDateTime from, LocalDateTime to);
}
