package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Project 工作流状态定义 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectWorkflowStateDTO {

  private String state;
  private String name;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agent;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environment;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String instructions;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String maxRuns;

  private Boolean enabled;
  private List<String> next;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
