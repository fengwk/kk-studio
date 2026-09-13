package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 输入流条目 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueInputDTO {

  private String issueId;
  private String sequence;
  private String kind;
  private String body;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String idempotencyKey;

  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
