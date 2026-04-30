package gitgalaxy.backend.model;

public record RepoMeta(
        String fullName,
        String ownerName,
        String ownerId,
        String repoName,
        String repoUrl,
        String primaryLanguage,
        String topics,
        Long starsTotal,
        Long forksTotal,
        String defaultBranch
) {}
