package gitgalaxy.backend.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 메인 은하 화면.
 * today      : 오늘(가장 최근 날짜) 트렌딩 순위 — 크기 = 스타 증가량, 밝기 = 활동량
 * recent     : 최근 며칠 트렌딩에 들었던 repo (오늘 제외) — 지난 날일수록 흐리게
 * background : 나머지 추적 repo — 희미한 배경 별 (밝기 = 최근 48시간 활동 점수)
 * links      : 같은 소유자 / README가 비슷한 repo 연결 (별자리)
 */
public record GalaxyResponse(LocalDate date, boolean complete, int days,
                             List<TodayStar> today, List<RecentStar> recent,
                             List<BackgroundStar> background, List<Link> links) {

    /** @param brightness 0~1 (하루 확정 전이면 최근 활동 점수로 대체, 없으면 null) */
    public record TodayStar(int rank, String fullName, int starsGained, int stargazers,
                            Double brightness, String language, String description) {}

    /** @param daysAgo 마지막으로 순위에 든 날이 며칠 전인지 (1 = 어제) */
    public record RecentStar(String fullName, int daysAgo, int appearances, int bestRank,
                             int starsGained, String language) {}

    /** @param brightness 0~1, 최근 48시간 활동이 없으면 0 */
    public record BackgroundStar(String fullName, String language, int stargazers, double brightness) {}

    /** @param type owner = 같은 소유자, similar = README 임베딩 유사도 */
    public record Link(String source, String target, String type, Double similarity) {}
}
