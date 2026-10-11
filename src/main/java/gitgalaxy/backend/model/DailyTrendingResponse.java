package gitgalaxy.backend.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 일간 트렌딩 응답.
 * @param complete true = UTC 하루가 끝나 S1(다음날 00시)까지 확정, false = 진행 중인 날의 "지금까지"
 */
public record DailyTrendingResponse(LocalDate date, boolean complete, List<Item> items) {

    /**
     * @param method     full = 당일 00시부터 / partial = 당일 처음 발견된 시점부터 (실제 증가량보다 작을 수 있음)
     * @param brightness 별 밝기 0~1 (같은 날 활동량 상대값). 하루가 끝나 활동량을 집계하기 전이면 null
     * @param activity   같은 날 GH Archive 활동량. 집계 전이면 null
     * @param trigger         계기 (규칙 기반 추정: Hacker News·릴리즈·신규 공개, 릴리즈 전후 스타 증가 속도)
     * @param triggerStrength strong / medium / weak / none
     * @param appeal          끌린 점 (설명·릴리즈 노트·HN 댓글 요약)
     * @param reason          화면 표시용 한 줄 ("계기(추정, 근거 강함): … / 끌린 점: …")
     */
    public record Item(int rank, String fullName, int starsGained, int stargazers, String method,
                       String description, String language, Double brightness, Activity activity,
                       String reason, String trigger, String triggerStrength, String appeal, TrendEvidence evidence) {}

    /** @param hours 집계에 쓴 GH Archive 시간대 수 (24 미만이면 일부 시간대 누락) */
    public record Activity(int pushes, int prsOpened, int prsMerged, int issuesOpened, int issuesClosed,
                           int releases, int forks, double score, int hours) {}
}
