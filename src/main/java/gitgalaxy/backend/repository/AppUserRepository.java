package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByGithubId(Long github_id);
}
