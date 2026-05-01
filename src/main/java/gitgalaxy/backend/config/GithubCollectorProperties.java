package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "github.collector")
@Data
public class GithubCollectorProperties {

    private String token = "";

    /** repo 목록 파일 경로 (.json 또는 .csv) */
    private String repoListPath = "repos.json";

    /** JSONL 출력 디렉토리 */
    private String outputDir = "output";

    /** 이미 수집된 repo skip 여부 */
    private boolean skipExisting = true;

    /** 최대 재시도 횟수 */
    private int maxRetries = 3;

    /** 첫 번째 재시도 대기 시간 (ms), 이후 exponential backoff */
    private long retryDelayMs = 1000;

    /** Spring cron 표현식 (기본: 1시간마다) */
    private String cronExpression = "0 * * * * *";
}
