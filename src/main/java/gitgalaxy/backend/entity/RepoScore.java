package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "repo_scores",
    indexes = @Index(name = "idx_scores_repo", columnList = "repo_owner, repo_name, scored_at")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String repoOwner;
    private String repoName;

    private LocalDateTime scoredAt;

    private double activityScore;   // 단기 활동 지수 (0~100)
    private double healthScore;     // 장기 건강 지수 (0~100)
}
