package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    Optional<Repo> findByFullName(String fullName);
    Optional<Repo> findByOwnerAndName(String owner, String name);

    @Query("SELECT r FROM Repo r WHERE LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(r.owner) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Repo> searchByKeyword(@Param("keyword") String keyword);
}
