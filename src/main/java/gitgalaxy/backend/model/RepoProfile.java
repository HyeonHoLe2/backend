package gitgalaxy.backend.model;

import java.util.List;

/** GraphQL 한 번으로 받은 repo 메타 + 토픽 + README 원문 (레포 단위 임베딩 재료) */
public record RepoProfile(RepoMeta meta, List<String> topics, String readme) {}
