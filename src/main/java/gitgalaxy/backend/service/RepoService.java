package gitgalaxy.backend.service;

import gitgalaxy.backend.document.RepoDocument;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.model.RepoResponse;
import gitgalaxy.backend.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoService {

    private final RepoRepository repoRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    public List<RepoResponse> search(String keyword) {
        try {
            var query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .must(m -> m.multiMatch(mm -> mm
                                    .query(keyword)
                                    .fields(List.of("name^4", "fullName^4", "description^2", "owner"))
                                    .fuzziness("AUTO")))
                            // 삭제·비공개로 추적 해제된 repo 제외 (점수에 영향 없는 filter)
                            .filter(f -> f.term(t -> t.field("tracked").value(true)))))
                    .build();

            SearchHits<RepoDocument> hits = elasticsearchOperations.search(query, RepoDocument.class);

            if (hits.hasSearchHits()) {
                List<Long> ids = hits.getSearchHits().stream()
                        .map(h -> Long.parseLong(h.getContent().getId()))
                        .toList();
                // findAllById는 순서를 보장하지 않음 → ES 관련도 순서대로 다시 정렬 (DB에서 사라진 id는 제외)
                Map<Long, Repo> byId = repoRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Repo::getId, Function.identity()));
                return ids.stream()
                        .map(byId::get)
                        .filter(r -> r != null && r.isTracked())
                        .map(RepoResponse::from)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Elasticsearch search failed, falling back to DB: {}", e.getMessage());
        }

        return repoRepository.searchByKeyword(keyword).stream()
                .map(RepoResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RepoResponse getRepoById(Long repoId) {
        return repoRepository.findById(repoId)
                .map(RepoResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found: " + repoId));
    }

    @Transactional(readOnly = true)
    public RepoResponse getRepo(String owner, String repo) {
        return repoRepository.findByOwnerAndName(owner, repo)
                .map(RepoResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found: " + owner + "/" + repo));
    }
}
