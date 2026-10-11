package gitgalaxy.backend.service;

import java.util.regex.Pattern;

/**
 * README에서 유사도 비교에 방해되는 형식 요소 제거 → 본문 텍스트만.
 * README 첫 부분은 배지·로고·언어 링크 같은 HTML이 많아, 그대로 임베딩하면 주제가 다른 레포끼리도 유사도가 높게 나옴.
 */
public final class ReadmeCleaner {

    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BADGE_LINK = Pattern.compile("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern TABLE_RULE = Pattern.compile("(?m)^\\s*[|:\\- ]+\\s*$");
    private static final Pattern MD_SYMBOL = Pattern.compile("[#>*_`|]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ReadmeCleaner() {}

    public static String clean(String markdown, int maxChars) {
        if (markdown == null || markdown.isBlank()) return "";
        String s = CODE_BLOCK.matcher(markdown).replaceAll(" ");
        s = COMMENT.matcher(s).replaceAll(" ");
        s = BADGE_LINK.matcher(s).replaceAll(" ");
        s = IMAGE.matcher(s).replaceAll(" ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll(" ");
        s = TABLE_RULE.matcher(s).replaceAll(" ");
        s = MD_SYMBOL.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }
}
