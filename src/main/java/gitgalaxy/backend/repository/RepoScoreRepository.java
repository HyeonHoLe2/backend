package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.RepoScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RepoScoreRepository extends JpaRepository<RepoScore, Long> {

    Optional<RepoScore> findTopByRepoOwnerAndRepoNameOrderByScoredAtDesc(
            String repoOwner, String repoName);

    List<RepoScore> findByRepoOwnerAndRepoNameOrderByScoredAtAsc(
            String repoOwner, String repoName);

    /** 최신 스코어 기준 상위 N개 (트렌딩 API용) */
    @Query(value = """
            SELECT DISTINCT ON (repo_owner, repo_name)
                repo_owner, repo_name, activity_score, health_score, scored_at
            FROM repo_scores
            ORDER BY repo_owner, repo_name, scored_at DESC
            """, nativeQuery = true)
    List<Object[]> findLatestScorePerRepo();
}
