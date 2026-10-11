package gitgalaxy.backend.service;

import gitgalaxy.backend.metrics.CollectorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * RAG 답변 캐시 (Cache-Aside, Redis).
 * - 키: rag:{scope}:v{버전}:{정규화한 질문 해시}. scope = repo:{owner}/{repo} 또는 global
 * - 무효화: KEYS로 지우지 않고 scope 버전만 올림(INCR 1회) → 이전 버전 키는 아무도 안 읽고 TTL로 만료
 * - Redis 장애 시 fail-open: 캐시 없이 원래 경로로 답변 (예외를 요청까지 올리지 않음)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagAnswerCache {

    private static final String PREFIX = "rag:";
    private static final String GLOBAL = "global";

    private final StringRedisTemplate redis;
    private final CollectorMetrics collectorMetrics;

    @Value("${rag.cache.ttl:1h}")
    private Duration ttl;

    public static String repoScope(String owner, String repo) {
        return "repo:" + owner + "/" + repo;
    }

    public static String globalScope() {
        return GLOBAL;
    }

    /** 캐시에 있으면 반환, 없으면 loader로 만든 답변을 저장 후 반환. loader가 null을 주면 저장하지 않음 */
    public String getOrLoad(String scope, String question, Supplier<String> loader) {
        String key = key(scope, question);
        if (key != null) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    collectorMetrics.ragCache(scopeTag(scope), "hit");
                    return cached;
                }
                collectorMetrics.ragCache(scopeTag(scope), "miss");
            } catch (Exception e) {
                key = null;
                collectorMetrics.ragCache(scopeTag(scope), "error");
                log.warn("RAG 캐시 조회 실패 (캐시 없이 진행): {}", e.getMessage());
            }
        } else {
            collectorMetrics.ragCache(scopeTag(scope), "error");
        }

        String answer = loader.get();
        if (answer != null && key != null) {
            try {
                redis.opsForValue().set(key, answer, ttl);
            } catch (Exception e) {
                log.warn("RAG 캐시 저장 실패: {}", e.getMessage());
            }
        }
        return answer;
    }

    /** 레포 문서가 바뀌면 그 레포 답변과 전체 검색 답변을 무효화 */
    public void evictRepo(String owner, String repo) {
        bump(repoScope(owner, repo));
    }

    public void evictGlobal() {
        bump(GLOBAL);
    }

    private void bump(String scope) {
        try {
            redis.opsForValue().increment(versionKey(scope));
        } catch (Exception e) {
            log.warn("RAG 캐시 무효화 실패 {} (TTL 만료까지 이전 답변 유지): {}", scope, e.getMessage());
        }
    }

    /** 버전 조회 실패 시 null → 캐시를 쓰지 않음 */
    private String key(String scope, String question) {
        String ver;
        try {
            ver = redis.opsForValue().get(versionKey(scope));
        } catch (Exception e) {
            log.warn("RAG 캐시 버전 조회 실패 (캐시 없이 진행): {}", e.getMessage());
            return null;
        }
        return PREFIX + scope + ":v" + (ver == null ? "0" : ver) + ":" + hash(normalize(question));
    }

    private static String versionKey(String scope) {
        return PREFIX + "ver:" + scope;
    }

    /** 대소문자·공백·끝 문장부호 차이는 같은 질문으로 취급 */
    static String normalize(String question) {
        return question.strip()
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[\\s?？!！.。~]+$", "");
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String scopeTag(String scope) {
        return GLOBAL.equals(scope) ? "global" : "repo";
    }
}
