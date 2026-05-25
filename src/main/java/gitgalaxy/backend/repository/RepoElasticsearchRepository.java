package gitgalaxy.backend.repository;

import gitgalaxy.backend.document.RepoDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface RepoElasticsearchRepository extends ElasticsearchRepository<RepoDocument, String> {}
