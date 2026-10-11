package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trending.retention")
@Data
public class RetentionProperties {

    private boolean enabled = false;

    /** 최근 N일 동안 */
    private int windowDays = 7;

    /** 일간 트렌딩 이 순위 안에 한 번도 들지 못한 repo는 삭제 */
    private int rankCutoff = 100;
}
