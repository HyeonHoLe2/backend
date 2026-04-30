package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "repo")
@Getter @Setter @NoArgsConstructor
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "repo_id")
    private Long repoId;

    @Column(name = "full_name", nullable = false, unique = true)
    private String fullName;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "repo_name", length = 50, nullable = false)
    private String repoName;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "primary_language")
    private String primaryLanguage;

    @Column(name = "topics")
    private String topics;

    @Column(name = "stars_total", nullable = false)
    private Long starsTotal = 0L;

    @Column(name = "forks_total", nullable = false)
    private Long forksTotal = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "trend_status", nullable = false)
    private TrendStatus trendStatus = TrendStatus.NEW;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (firstSeenAt == null) firstSeenAt = now;
        lastSeenAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        lastSeenAt = Instant.now();
    }
}
