package gitgalaxy.backend.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GitHub repo tree에서 문서 파일만 필터링.
 *
 * 포함 기준:
 *   - 루트의 README.md, *.md, *.rst
 *   - docs/, doc/, documentation/
 *   - examples/, example/
 *   - guides/, guide/, tutorial/, tutorials/, wiki/
 *
 * 제외 기준:
 *   - CHANGELOG, LICENSE, CONTRIBUTING 등 메타 파일
 *   - test/, tests/, node_modules/, .github/, build/, dist/
 *   - 숨김 디렉토리(.으로 시작)
 */
@Service
public class FileSelector {

    private static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "changelog.md", "changes.md", "history.md",
            "license.md", "licence.md",
            "contributing.md", "code_of_conduct.md",
            "security.md", "authors.md", "maintainers.md",
            "codeowners", "funding.yml",
            "agents.md",
            "pull_request_template.md",
            "issue_template.md",
            "support.md",
            "governance.md"
    );

    private static final List<String> INCLUDED_DIR_PREFIXES = List.of(
            "docs/", "doc/", "documentation/",
            "guides/", "guide/",
            "tutorial/", "tutorials/",
            "wiki/"
    );

    private static final List<String> EXCLUDED_DIR_PREFIXES = List.of(
            "node_modules/", "vendor/", "build/", "dist/",
            ".github/", ".git/",
            "scripts/", "ci/", ".circleci/", ".github/workflows/"
    );

    private static final List<String> EXCLUDED_DIR_CONTAINS = List.of(
            "/node_modules/", "/vendor/", "/test/", "/tests/",
            "/spec/", "/__tests__/", "/build/", "/dist/",
            "/mock/", "/mocks/",
            "/fixtures/",
            "/coverage/"
    );

    private static final Set<String> INCLUDED_EXTENSIONS = Set.of(".md", ".mdx", ".rst");

    // ────────────────────────────────────────────────

    public List<String> filterDocFiles(List<String> paths) {
        return paths.stream()
                .filter(this::isDocumentFile)
                .collect(Collectors.toList());
    }

    public boolean isDocumentFile(String path) {
        if (path == null || path.isBlank()) return false;

        String lower = path.toLowerCase();

        // 제외: 숨김 디렉토리
        if (lower.startsWith(".") || lower.contains("/.")) return false;

        // 제외: 알려진 비문서 디렉토리 prefix
        for (String excluded : EXCLUDED_DIR_PREFIXES) {
            if (lower.startsWith(excluded)) return false;
        }

        // 제외: 경로 중간에 있는 비문서 디렉토리
        for (String excluded : EXCLUDED_DIR_CONTAINS) {
            if (lower.contains(excluded)) return false;
        }

        // 확장자 체크
        String ext = getExtension(lower);
        if (!INCLUDED_EXTENSIONS.contains(ext)) return false;

        // 제외 파일명 체크
        String filename = getFilename(lower);
        if (EXCLUDED_FILENAMES.contains(filename)) return false;

        // 루트 레벨 파일 (경로에 / 없음) → 허용
        if (!path.contains("/")) return true;

        // 허용된 디렉토리 prefix
        for (String included : INCLUDED_DIR_PREFIXES) {
            if (lower.startsWith(included)) return true;
        }

        return false;
    }

    private String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "";
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(dot) : "";
    }

    private String getFilename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
