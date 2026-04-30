package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {
    Optional<UserFavorite> findByUserIdAndRepoId(Long userId, Long repoId);
    List<UserFavorite> findByUserId(Long userId);
    boolean existsByUserIdAndRepoId(Long userId, Long repoId);
}
