package gitgalaxy.backend.model;

import java.util.List;

/** push 1건에 포함된 커밋 (totalCommits는 compare API 기준 전체 수, commits는 최대 250개) */
public record PushCommits(
        int totalCommits,
        List<Commit> commits
) {
    public record Commit(String sha, String message) {}
}
