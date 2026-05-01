package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "batch_process")
@Getter @Setter @NoArgsConstructor
public class BatchProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "target_from", nullable = false)
    private Instant targetFrom;

    @Column(name = "target_to", nullable = false)
    private Instant targetTo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "total_count")
    private Long totalCount;

    @Column(name = "success_count")
    private Long successCount;

    @Column(name = "fail_count")
    private Long failCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (startedAt == null) startedAt = Instant.now();
    }
}
