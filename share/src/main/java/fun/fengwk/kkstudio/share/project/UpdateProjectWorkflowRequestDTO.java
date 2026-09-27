package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 更新项目工作流请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProjectWorkflowRequestDTO {

  private String expectedVersion;
  private ProjectWorkflowDTO workflow;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
