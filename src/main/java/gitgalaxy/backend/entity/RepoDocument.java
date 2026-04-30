package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "repo_document")
@Getter @Setter @NoArgsConstructor
public class RepoDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(name = "origin_url", nullable = false)
    private String originUrl;

    @Column(name = "title")
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (collectedAt == null) collectedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
