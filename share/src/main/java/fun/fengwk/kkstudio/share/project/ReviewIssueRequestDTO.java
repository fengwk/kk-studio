package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 人工评审请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewIssueRequestDTO {

  private String decision;
  private String summary;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String verification;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String terminalActionId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String observedSpecRevision;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String observedInputSequence;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
