package gitgalaxy.backend.model;

import gitgalaxy.backend.entity.AppUser;

import java.time.Instant;

public record UserResponse(
        Long userId,
        Long githubId,
        String githubLogin,
        String profileUrl,
        Instant createdAt
) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getUserId(),
                user.getGithubId(),
                user.getGithubLogin(),
                user.getProfileUrl(),
                user.getCreatedAt()
        );
    }
}
