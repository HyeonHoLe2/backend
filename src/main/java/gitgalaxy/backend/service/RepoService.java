package gitgalaxy.backend.service;

import gitgalaxy.backend.document.RepoDocument;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoService {

    private final RepoRepository repoRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    public List<RepoResponse> search(String keyword) {
        try {
            var query = NativeQuery.builder()
                    .withQuery(q -> q
                            .multiMatch(mm -> mm
                                    .query(keyword)
                                    .fields(List.of("name^4", "fullName^4", "description^2", "owner"))
                                    .fuzziness("AUTO")
                            )
                    )
                    .build();

            SearchHits<RepoDocument> hits = elasticsearchOperations.search(query, RepoDocument.class);

            if (hits.hasSearchHits()) {
                List<Long> ids = hits.getSearchHits().stream()
                        .map(h -> Long.parseLong(h.getContent().getId()))
                        .toList();
                return repoRepository.findAllById(ids).stream()
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
