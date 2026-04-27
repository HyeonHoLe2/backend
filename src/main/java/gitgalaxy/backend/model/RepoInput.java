package gitgalaxy.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * repos.json / repos.csv 에서 읽어들이는 수집 대상 repo 정보
 */
public record RepoInput(
        @JsonProperty("owner") String owner,
        @JsonProperty("repo") String repo
) {
    public String fullName() {
        return owner + "/" + repo;
    }
}
