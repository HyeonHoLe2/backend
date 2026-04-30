package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "repo_event")
@Getter @Setter @NoArgsConstructor
public class RepoEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_action")
    private String eventAction;

    @Column(name = "actor_login", nullable = false)
    private String actorLogin;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "raw_json", columnDefinition = "json")
    private String rawJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
