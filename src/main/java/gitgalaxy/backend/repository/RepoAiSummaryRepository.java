package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.RepoAiSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoAiSummaryRepository extends JpaRepository<RepoAiSummary, Long> {

    Optional<RepoAiSummary> findByOwnerAndRepo(String owner, String repo);
}

