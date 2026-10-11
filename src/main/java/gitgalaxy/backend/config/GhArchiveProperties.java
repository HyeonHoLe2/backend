package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gharchive")
@Data
public class GhArchiveProperties {
    private String baseUrl = "https://data.gharchive.org";

    /** 신규 발견 기준: 1시간 내 PushEvent 최소 횟수 */
    private int minPushesForDiscovery = 1;

    /** 신규 발견 기준: 1시간 내 IssuesEvent 최소 횟수 */
    private int minIssuesForDiscovery = 1;

    /** 시간당 최대 온보딩 repo 수 (GitHub API rate limit / LLM 비용 보호) */
    private int maxDiscoveryPerHour = 100;

    /** 추적 repo push 커밋 조회: 시간당 최대 조회 push 수 */
    private int maxCommitFetchPerHour = 1500;

    /** 추적 repo push 커밋 조회: repo당 시간당 최대 호출 수 (봇 repo 독점 방지) */
    private int maxCommitFetchPerRepo = 10;

    /** 커밋 조회 GraphQL 동시 요청 수 (묶음 요청이 무거워 너무 높이면 502 → 소수로 제한) */
    private int commitFetchConcurrency = 4;

    /** 커밋 조회 GraphQL 1회에 묶는 push 수 */
    private int commitFetchBatchSize = 25;

    /** push당 거슬러 올라갈 커밋 수 (이보다 많은 커밋의 push는 이 수까지만 셈) */
    private int commitHistoryDepth = 30;

    /** 시간별 파일 다운로드 타임아웃(초). 정상 시 압축 약 15MB */
    private int downloadTimeoutSeconds = 180;

    /** 시간별 파일 다운로드 최대 시도 횟수 */
    private int downloadMaxAttempts = 3;

    /** 실패 시간대 최대 처리 횟수 (첫 시도 포함). 넘으면 dead → 알림 대상 */
    private int retryMaxAttempts = 6;

    /** 매시 배치에서 직전 시간대 다음에 이어서 재처리할 실패 시간대 수 (배치가 1시간을 넘지 않도록) */
    private int retryPerRun = 2;

    /** 스케줄이 돌지 않아(절전·지연) 기록조차 없는 시간대를 찾는 범위(시간) */
    private int missingLookbackHours = 48;
}
