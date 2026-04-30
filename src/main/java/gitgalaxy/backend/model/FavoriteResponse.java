package gitgalaxy.backend.model;

import gitgalaxy.backend.entity.UserFavorite;

import java.time.Instant;

public record FavoriteResponse(
        Long favoriteId,
        Long repoId,
        Instant createdAt
) {
    public static FavoriteResponse from(UserFavorite favorite) {
        return new FavoriteResponse(
                favorite.getFavoriteId(),
                favorite.getRepoId(),
                favorite.getCreatedAt()
        );
    }
}
