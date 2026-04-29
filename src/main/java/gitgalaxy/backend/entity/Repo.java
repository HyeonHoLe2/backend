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

    private String owner;
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private int starCount;
    private String defaultBranch;
    private boolean tracked;
    private String language;

    private LocalDateTime lastCollectedAt;
    private LocalDateTime createdAt;
}
