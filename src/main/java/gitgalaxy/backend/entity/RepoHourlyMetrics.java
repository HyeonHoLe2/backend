package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "repo_hourly_metrics",
    indexes = @Index(name = "idx_metrics_repo_hour", columnList = "repo_owner, repo_name, hour_bucket"),
    uniqueConstraints = @UniqueConstraint(columnNames = {"repo_owner", "repo_name", "hour_bucket"})
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoHourlyMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String repoOwner;
    private String repoName;

    private LocalDateTime hourBucket;   // 시간 단위로 truncate (예: 2025-04-29T14:00)

    private int watchCount;             // WatchEvent (star 증가량)
    private int pushCount;              // PushEvent (commit 수)
    private int prCount;                // PullRequestEvent
    private int issueCount;             // IssuesEvent

    private LocalDateTime createdAt;
}
