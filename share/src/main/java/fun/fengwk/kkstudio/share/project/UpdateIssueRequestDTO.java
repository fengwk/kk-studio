package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 更新 Issue 请求 DTO，含 expectedVersion CAS 版本。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateIssueRequestDTO {

  private String expectedVersion;
  private String title;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String description;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String assigneeAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String reviewerAgentName;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
