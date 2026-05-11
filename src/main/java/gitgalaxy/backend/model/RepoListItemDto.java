package gitgalaxy.backend.model;

import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.entity.RepoHourlyMetrics;

import java.time.LocalDateTime;

public record RepoListItemDto(
        Long id,
        String fullName,
        String owner,
        String name,
        String description,
        String language,
        double brightnessScore,
        double activeScore,
        double healthScore,
        double sizeScore,
        LocalDateTime latestBucket
) {
    public static RepoListItemDto of(Repo repo, RepoHourlyMetrics latest) {
        return new RepoListItemDto(
                repo.getId(),
                repo.getFullName(),
                repo.getOwner(),
                repo.getName(),
                repo.getDescription(),
                repo.getLanguage(),
                latest != null ? latest.getBrightnessScore() : 0.0,
                latest != null ? latest.getActiveScore() : 0.0,
                latest != null ? latest.getHealthScore() : 0.0,
                latest != null ? latest.getSizeScore() : 0.0,
                latest != null ? latest.getBucket() : null
        );
    }
}
