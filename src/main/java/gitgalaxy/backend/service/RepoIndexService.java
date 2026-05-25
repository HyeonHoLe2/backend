package gitgalaxy.backend.service;

import gitgalaxy.backend.document.RepoDocument;
import gitgalaxy.backend.entity.Repo;
import gitgalaxy.backend.repository.RepoElasticsearchRepository;
import gitgalaxy.backend.repository.RepoRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoIndexService {

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
            List<Repo> repos = repoRepository.findAll();
            List<RepoDocument> docs = repos.stream().map(this::toDocument).toList();
            repoElasticsearchRepository.saveAll(docs);
            log.info("Indexed {} repos in Elasticsearch", docs.size());
        } catch (Exception e) {
            log.warn("Failed to index repos in Elasticsearch on startup: {}", e.getMessage());
        }
    }

    public void index(Repo repo) {
        try {
            repoElasticsearchRepository.save(toDocument(repo));
        } catch (Exception e) {
            log.warn("Failed to index repo {} in Elasticsearch: {}", repo.getFullName(), e.getMessage());
        }
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
