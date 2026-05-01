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
}
