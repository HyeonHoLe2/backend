package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "repo_time")
@Getter @Setter @NoArgsConstructor
public class RepoTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "repo_time_id")
    private Long repoTimeId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "bucket", nullable = false)
    private Instant bucket;

    @Column(name = "commit_count")
    private Integer commitCount;

    @Column(name = "pr_created")
    private Integer prCreated;

    @Column(name = "pr_merged")
    private Integer prMerged;

    @Column(name = "issue_created")
    private Integer issueCreated;

    @Column(name = "issue_closed")
    private Integer issueClosed;

    @Column(name = "star_count")
    private Integer starCount;

    @Column(name = "release_count")
    private Integer releaseCount;

    @Column(name = "active_score")
    private BigDecimal activeScore;

    @Column(name = "health_score")
    private BigDecimal healthScore;

    @Column(name = "brightness_score")
    private BigDecimal brightnessScore;

    @Column(name = "size_score")
    private BigDecimal sizeScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
