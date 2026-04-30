package gitgalaxy.backend.repository;

import gitgalaxy.backend.entity.BatchProcess;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchProcessRepository extends JpaRepository<BatchProcess, Long> {
}
