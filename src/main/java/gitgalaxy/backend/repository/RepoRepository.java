package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    Optional<Repo> findByFullName(String fullName);
}
