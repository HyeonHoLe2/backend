package gitgalaxy.backend.model;

import gitgalaxy.backend.entity.RepoHourlyMetrics;

import java.time.LocalDateTime;

public record TimelinePointDto(
        LocalDateTime bucket,
        int watch,
        int commitCount,
        int prCreated,
        int prMerged,
        int issueOpened,
        int issueClosed,
        int starCount,
        int releaseCount,
        double activeScore,
        double healthScore,
        double brightnessScore,
        double sizeScore
) {
    public static TimelinePointDto from(RepoHourlyMetrics m) {
        return new TimelinePointDto(
                m.getBucket(),
                m.getWatch(),
                m.getCommitCount(),
                m.getPrCreated(),
                m.getPrMerged(),
                m.getIssueOpened(),
                m.getIssueClosed(),
                m.getStarCount(),
                m.getReleaseCount(),
                m.getActiveScore() != null ? m.getActiveScore() : 0.0,
                m.getHealthScore() != null ? m.getHealthScore() : 0.0,
                m.getBrightnessScore() != null ? m.getBrightnessScore() : 0.0,
                m.getSizeScore() != null ? m.getSizeScore() : 0.0
        );
    }
}
