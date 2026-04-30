package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "repo_discovery")
@Getter @Setter @NoArgsConstructor
public class RepoDiscovery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "discovery_id")
    private Long discoveryId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "discovery_source", length = 50, nullable = false)
    private String discoverySource;

    @Column(name = "discovery_type", length = 50, nullable = false)
    private String discoveryType;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "observed_rank", nullable = false)
    private Integer observedRank;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
