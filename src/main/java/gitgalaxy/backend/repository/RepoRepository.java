package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.Repo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;


import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.util.List;
import java.util.Optional;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    Optional<Repo> findByFullName(String fullName);

    List<Repo> findByTrackedTrue();

    /** 메타 갱신 대상: 가장 오래전에 수집된 추적 repo부터 (부하 테스트용 목데이터 mock-* 제외 → GitHub API 낭비 방지) */
    @Query("SELECT r FROM Repo r WHERE r.tracked = true AND r.owner NOT LIKE 'mock-%' ORDER BY r.lastCollectedAt ASC NULLS FIRST")
    List<Repo> findStalestTracked(Pageable pageable);

    /** 추적 repo full_name만 (엔티티 전체 로딩 없이) */
    @Query("SELECT r.fullName FROM Repo r WHERE r.tracked = true")
    List<String> findTrackedFullNames();

    List<Repo> findByFullNameIn(java.util.Collection<String> fullNames);

    Optional<Repo> findByOwnerAndName(String owner, String name);

    @Query("SELECT r FROM Repo r WHERE r.tracked = true AND (LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(r.owner) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Repo> searchByKeyword(@Param("keyword") String keyword);

}
