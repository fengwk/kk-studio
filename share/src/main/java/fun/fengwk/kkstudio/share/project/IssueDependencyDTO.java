package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 依赖关系边 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueDependencyDTO {

  private String issueId;
  private String dependsOnIssueId;
  private String projectId;
  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
