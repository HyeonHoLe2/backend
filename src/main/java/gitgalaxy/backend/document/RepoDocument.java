package gitgalaxy.backend.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "repos", createIndex = false)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String fullName;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String owner;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String name;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;

    @Field(type = FieldType.Keyword)
    private String language;

    @Field(type = FieldType.Integer)
    private int starCount;

    @Field(type = FieldType.Boolean)
    private boolean tracked;
}
