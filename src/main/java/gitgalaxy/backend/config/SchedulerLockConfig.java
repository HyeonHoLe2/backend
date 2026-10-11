package gitgalaxy.backend.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 정시 작업 분산 락 (ShedLock). 스케줄러는 인스턴스마다 있으므로, 실행 직전 shedlock 테이블에
 * 조건부 UPDATE로 실행권을 선점한 인스턴스만 작업을 실행하고 나머지는 그 회차를 건너뜀.
 * - 락 시각은 DB 시계 기준 (인스턴스 간 시계 차이 영향 없음)
 * - 작업마다 lockAtMostFor: 실행 중 인스턴스가 죽어도 이 시간이 지나면 락이 풀림 (최악 실행 시간보다 길게)
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build());
    }
}
