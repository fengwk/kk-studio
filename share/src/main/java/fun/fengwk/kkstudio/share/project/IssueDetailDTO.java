package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Issue 详情公开聚合 DTO，包含完整 spec、依赖边、输入流、历史 Runs 及当前/最近 Run。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueDetailDTO {

  private IssueDTO issue;
  private Boolean blocked;
  private List<IssueDependencyDTO> dependencies;
  private List<IssueInputDTO> inputs;
  private List<IssueRunDTO> runs;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private IssueRunDTO currentRun;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private IssueRunDTO latestRun;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
