package gitgalaxy.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gharchive")
@Data
public class GhArchiveProperties {
    private String baseUrl = "https://data.gharchive.org";
    private int minWatchCountForDiscovery = 50;
}
