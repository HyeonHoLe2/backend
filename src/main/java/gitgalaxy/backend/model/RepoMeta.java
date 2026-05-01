package gitgalaxy.backend.model;

public record RepoMeta(
<<<<<<< feat/2-scheduler
        String defaultBranch,
        String description,
        int stargazersCount
=======
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
>>>>>>> main
) {}
