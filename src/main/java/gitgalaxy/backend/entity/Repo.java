package gitgalaxy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "repos")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", unique = true, nullable = false)
    private String fullName;

    @Column(name = "owner")
    private String owner;

    @Column(name = "name")
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "star_count")
    private int starCount;

    // 기존 행은 fork_count/open_issues_count가 NULL일 수 있어 primitive int 사용 시
    // 엔티티 hydration에서 "Null value assigned to primitive" 예외 발생 → Integer(nullable)로 보관.
    // 스케줄러 수집 후 실제 값이 채워지며, 그 전에는 DTO 매핑에서 0으로 노출한다.
    @Column(name = "fork_count")
    private Integer forkCount;

    @Column(name = "open_issues_count")
    private Integer openIssuesCount;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "tracked")
    private boolean tracked;

    @Column(name = "language")
    private String language;

    @Column(name = "last_collected_at")
    private LocalDateTime lastCollectedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** ES에 마지막으로 반영한 시각. NULL = 반영 대기 (저장 트랜잭션에서 NULL로 표시 → RepoEsSyncService가 주기적으로 색인) */
    @Column(name = "es_synced_at")
    private LocalDateTime esSyncedAt;
}
