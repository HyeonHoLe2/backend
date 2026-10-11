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

    /** JSONL 출력 디렉토리 */
    private String outputDir = "output";

    /** 이미 수집된 repo skip 여부 */
    private boolean skipExisting = true;

    /** 최대 재시도 횟수 */
    private int maxRetries = 3;

    /** 첫 번째 재시도 대기 시간 (ms), 이후 exponential backoff */
    private long retryDelayMs = 1000;

    /**
     * GitHub API 여유분. 남은 요청 수가 이 값 이하면 리셋 시각까지 수집용 호출(온보딩·커밋 조회·메타 갱신)을 멈춤.
     * 사용자 요청(요약 API 등)과 예기치 못한 재시도를 위한 몫.
     */
    private int rateLimitReserve = 500;

    /** 메타(스타·포크 등) 갱신: 1회 실행당 갱신할 repo 수 (오래된 순) */
    private int metaRefreshBatchSize = 1000;

    /** 레포 프로필 GraphQL 동시 요청 수 (README 포함이라 요청이 무거움 → 너무 높이면 502) */
    private int graphqlConcurrency = 4;

    /** Spring cron 표현식 (기본: 1시간마다) */
    private String cronExpression = "0 * * * * *";
}
