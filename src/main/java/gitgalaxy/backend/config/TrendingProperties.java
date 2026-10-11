package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trending.daily")
@Data
public class TrendingProperties {

    /** 매시간 스냅샷 후보: 당일 GH Archive 스타 합계 상위 N개 */
    private int candidateLimit = 500;

    /** 후보 최소 GH Archive 스타 수 (당일 합계) */
    private int minArchiveStars = 1;

    /** 저장할 순위 개수 */
    private int topN = 100;

    /** 상위 N개는 repos에 온보딩(메타·README) + 다음날 계속 스냅샷 */
    private int featuredTop = 25;

    /**
     * 끌린 점(appeal) 재생성 기준: 전날보다 순위가 이만큼 이상 오르면 새로 생성 (급상승 = 새 계기 가능성).
     * 그 외에는 이전 날짜에 만든 appeal을 재사용. 전날 순위권 밖이면 (상위 topN + 1)위로 보고 계산.
     */
    private int appealRegenRankJump = 30;

    /** 상위 repo 근거(릴리즈·HN 등) 요약에 LLM 사용 여부 (false면 근거 목록만 저장) */
    private boolean reasonEnabled = true;

    /** 순위 상위 N개는 repos에 등록(메타·README 임베딩) → 은하 화면 언어·설명, 유사 레포 연결에 사용 */
    private int onboardTop = 100;

    /** 시간별 스타·스냅샷 보관 일수 */
    private int retentionDays = 8;
}
