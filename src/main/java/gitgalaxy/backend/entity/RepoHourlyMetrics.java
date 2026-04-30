package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "repo_time",
    indexes = @Index(name = "idx_repo_time_repo", columnList = "repo_owner, repo_name, bucket"),
    uniqueConstraints = @UniqueConstraint(columnNames = {"repo_owner", "repo_name", "bucket"})
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

    private LocalDateTime bucket;   // hour 단위 truncate (예: 2025-04-29T14:00)

    // ── 이벤트 집계 ──────────────────────────────────
    private int watch;              // WatchEvent (스타 이벤트)
    private int commitCount;        // PushEvent 커밋 수
    private int prCreated;          // PullRequestEvent action=opened
    private int prMerged;           // PullRequestEvent action=closed + merged=true
    private int issueOpened;        // IssuesEvent action=opened
    private int issueClosed;        // IssuesEvent action=closed
    private int starCount;          // 누적 스타 수 (WatchEvent 누계)
    private int releaseCount;       // ReleaseEvent

    // ── 스코어 (ERD repo_time 통합) ──────────────────
    private double activeScore;     // 단기 활동 지수 (0~100)
    private double healthScore;     // 장기 건강 지수 (0~100)
    private double brightnessScore; // 3D 별 밝기 (0~100)
    private double sizeScore;       // 3D 별 크기 (0~100)

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
