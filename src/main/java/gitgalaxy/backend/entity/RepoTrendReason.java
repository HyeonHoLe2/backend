package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "repo_trend_reasons",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repo_trend_reasons_owner_repo",
                columnNames = {"owner", "repo"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class RepoTrendReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private String trend;

    @Column(name = "change_rate")
    private double changeRate;

    @Lob
    @Column
    private String reason;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
