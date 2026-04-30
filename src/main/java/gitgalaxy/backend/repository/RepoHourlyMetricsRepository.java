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

    /** bucket 기준 upsert — 이벤트 집계만, 스코어는 updateScores()로 별도 업데이트 */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO repo_time
                (repo_owner, repo_name, bucket,
                 watch, commit_count, pr_created, pr_merged,
                 issue_opened, issue_closed, star_count, release_count,
                 created_at, updated_at)
            VALUES
                (:owner, :name, :bucket,
                 :watch, :commitCount, :prCreated, :prMerged,
                 :issueOpened, :issueClosed, :starCount, :releaseCount,
                 NOW(), NOW())
            ON CONFLICT (repo_owner, repo_name, bucket) DO UPDATE SET
                watch         = EXCLUDED.watch,
                commit_count  = EXCLUDED.commit_count,
                pr_created    = EXCLUDED.pr_created,
                pr_merged     = EXCLUDED.pr_merged,
                issue_opened  = EXCLUDED.issue_opened,
                issue_closed  = EXCLUDED.issue_closed,
                star_count    = EXCLUDED.star_count,
                release_count = EXCLUDED.release_count,
                updated_at    = NOW()
            """, nativeQuery = true)
    void upsert(@Param("owner") String owner,
                @Param("name") String name,
                @Param("bucket") LocalDateTime bucket,
                @Param("watch") int watch,
                @Param("commitCount") int commitCount,
                @Param("prCreated") int prCreated,
                @Param("prMerged") int prMerged,
                @Param("issueOpened") int issueOpened,
                @Param("issueClosed") int issueClosed,
                @Param("starCount") int starCount,
                @Param("releaseCount") int releaseCount);

    /** 스코어만 업데이트 (upsert 후 ScoreService가 호출) */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE repo_time SET
                active_score     = :activeScore,
                health_score     = :healthScore,
                brightness_score = :brightnessScore,
                size_score       = :sizeScore,
                updated_at       = NOW()
            WHERE repo_owner = :owner AND repo_name = :name AND bucket = :bucket
            """, nativeQuery = true)
    void updateScores(@Param("owner") String owner,
                      @Param("name") String name,
                      @Param("bucket") LocalDateTime bucket,
                      @Param("activeScore") double activeScore,
                      @Param("healthScore") double healthScore,
                      @Param("brightnessScore") double brightnessScore,
                      @Param("sizeScore") double sizeScore);

    /** 기간 내 시간별 데이터 조회 (스코어 차트 + 메트릭 차트 공통 사용) */
    List<RepoHourlyMetrics> findByRepoOwnerAndRepoNameAndBucketBetweenOrderByBucketAsc(
            String repoOwner, String repoName, LocalDateTime from, LocalDateTime to);

    /** 최신 스코어 기준 상위 repo (트렌딩 API용) */
    @Query(value = """
            SELECT DISTINCT ON (repo_owner, repo_name)
                *
            FROM repo_time
            ORDER BY repo_owner, repo_name, bucket DESC
            """, nativeQuery = true)
    List<RepoHourlyMetrics> findLatestPerRepo();
}
