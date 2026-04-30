package gitgalaxy.backend.model;

import gitgalaxy.backend.entity.Repo;

import java.time.LocalDateTime;

public record RepoResponse(
        Long id,
        String fullName,
        String owner,
        String name,
        String description,
        int starCount,
        String language,
        String defaultBranch,
        boolean tracked,
        LocalDateTime lastCollectedAt
) {
    public static RepoResponse from(Repo repo) {
        return new RepoResponse(
                repo.getId(),
                repo.getFullName(),
                repo.getOwner(),
                repo.getName(),
                repo.getDescription(),
                repo.getStarCount(),
                repo.getLanguage(),
                repo.getDefaultBranch(),
                repo.isTracked(),
                repo.getLastCollectedAt()
        );
    }
}
