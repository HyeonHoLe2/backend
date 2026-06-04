package gitgalaxy.backend.model;

import java.util.List;
import java.util.Set;

public record GhArchiveResult(
        List<ChunkDocument> chunks,
        Set<String> affectedRepos  // "owner/name" 형태
) {}
