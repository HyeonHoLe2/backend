package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.RepoTrendReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoTrendReasonRepository extends JpaRepository<RepoTrendReason, Long> {

    Optional<RepoTrendReason> findByOwnerAndRepo(String owner, String repo);
}
