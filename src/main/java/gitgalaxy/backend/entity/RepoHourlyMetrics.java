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

    @Column(name = "repo_owner")
    private String repoOwner;

    @Column(name = "repo_name")
    private String repoName;

    @Column(name = "bucket")
    private LocalDateTime bucket;

    @Column(name = "watch")
    private int watch;

    @Column(name = "commit_count")
    private int commitCount;

    @Column(name = "pr_created")
    private int prCreated;

    @Column(name = "pr_merged")
    private int prMerged;

    @Column(name = "issue_opened")
    private int issueOpened;

    @Column(name = "issue_closed")
    private int issueClosed;

    @Column(name = "star_count")
    private int starCount;

    @Column(name = "release_count")
    private int releaseCount;

    @Column(name = "active_score")
    private double activeScore;

    @Column(name = "health_score")
    private double healthScore;

    @Column(name = "brightness_score")
    private double brightnessScore;

    @Column(name = "size_score")
    private double sizeScore;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
