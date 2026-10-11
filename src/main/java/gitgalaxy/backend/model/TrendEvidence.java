package gitgalaxy.backend.model;

import java.util.List;

/**
 * 트렌딩 repo가 뜬 시기에 확인된 사실들 (원인 단정이 아니라 근거 목록).
 * @param description   repo 설명 (GitHub "About")
 * @param newRepo       트렌딩 날짜 기준 30일 이내 생성
 * @param recentRelease 트렌딩 날짜 2일 전 ~ 당일 사이 릴리즈
 * @param hnPosts       트렌딩 날짜 3일 전 ~ 당일 사이 Hacker News 게시글 (10점 이상, 점수순)
 */
public record TrendEvidence(String description, List<String> topics, String language,
                            String createdAt, boolean newRepo, String ownerType, Integer ownerFollowers,
                            Release recentRelease, List<HnPost> hnPosts) {

    /** @param notes 릴리즈 노트 앞부분 */
    public record Release(String tagName, String name, String publishedAt, String notes) {}

    /** @param topComments 상위 댓글 몇 개 (앞부분만) */
    public record HnPost(String title, int points, int comments, String createdAt, String hnUrl, List<String> topComments) {}

    /** 원인 근거(새 레포·릴리즈·HN)가 하나도 없음. 설명·토픽은 근거로 치지 않음 */
    public boolean isEmpty() {
        return !newRepo && recentRelease == null && (hnPosts == null || hnPosts.isEmpty());
    }
}
