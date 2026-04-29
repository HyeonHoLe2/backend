package gitgalaxy.backend.model;

public record RepoMeta(
        String defaultBranch,
        String description,
        int stargazersCount
) {}
