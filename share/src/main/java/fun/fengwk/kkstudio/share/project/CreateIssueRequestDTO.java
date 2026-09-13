package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建 Issue 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateIssueRequestDTO {

  private String title;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String description;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String assigneeAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String reviewerAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String initialStatus;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
