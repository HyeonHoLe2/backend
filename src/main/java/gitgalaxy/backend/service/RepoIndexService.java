package gitgalaxy.backend.service;

import gitgalaxy.backend.document.RepoDocument;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.repository.RepoElasticsearchRepository;
import gitgalaxy.backend.repository.RepoRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoIndexService {

    private static final int INDEX_PAGE_SIZE = 5_000;

    private final RepoRepository repoRepository;
    private final RepoElasticsearchRepository repoElasticsearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    @PostConstruct
    public void indexAll() {
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(RepoDocument.class);
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
                log.info("Created Elasticsearch index 'repos'");
            }
            long dbCount = repoRepository.count();
            long esCount = repoElasticsearchRepository.count();
            if (dbCount == esCount) {
                log.info("Elasticsearch 색인 최신 상태 ({}건) → 시작 시 재색인 생략", esCount);
                return;
            }
            // 대량 데이터에서 전체를 한 번에 메모리에 올리지 않도록 페이지 단위로 색인
            int page = 0, indexed = 0;
            Page<Repo> slice;
            do {
                slice = repoRepository.findAll(PageRequest.of(page++, INDEX_PAGE_SIZE, Sort.by("id")));
                repoElasticsearchRepository.saveAll(slice.getContent().stream().map(this::toDocument).toList());
                indexed += slice.getNumberOfElements();
            } while (slice.hasNext());
            log.info("Indexed {} repos in Elasticsearch (DB {}건, 기존 ES {}건)", indexed, dbCount, esCount);
        } catch (Exception e) {
            log.warn("Failed to index repos in Elasticsearch on startup: {}", e.getMessage());
        }
    }

    /** 여러 repo를 bulk 요청 1회로 색인. 실패하면 예외 → 호출 측에서 반영 표시를 하지 않아 다음 주기에 재시도 */
    public void indexBulk(List<Repo> repos) {
        if (repos.isEmpty()) return;
        repoElasticsearchRepository.saveAll(repos.stream().map(this::toDocument).toList());
    }

    public RepoDocument toDocument(Repo repo) {
        return RepoDocument.builder()
                .id(String.valueOf(repo.getId()))
                .fullName(repo.getFullName())
                .owner(repo.getOwner())
                .name(repo.getName())
                .description(repo.getDescription())
                .language(repo.getLanguage())
                .starCount(repo.getStarCount())
                .tracked(repo.isTracked())
                .build();
    }
}
