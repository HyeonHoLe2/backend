package gitgalaxy.backend.model;

import java.util.List;
import java.util.Set;

public record GhArchiveResult(
        List<ChunkDocument> chunks,
        Set<String> affectedRepos,   // "owner/name" 형태
        List<String> discoveredRepos, // 신규 온보딩 대상, 활동량 내림차순
        String status                 // success | not_found | failed
) {
    public static GhArchiveResult empty(String status) {
        return new GhArchiveResult(List.of(), Set.of(), List.of(), status);
    }
}
