package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Issue 详情聚合：资料、活动窗口、Run 投影、阶段额度与 Agent 绑定。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueDetailDTO {

  private IssueDTO issue;
  private List<IssueActivityDTO> activities;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String nextActivityCursor;

  private List<IssueRunDTO> runs;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable IssueRunDTO currentRun;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable IssueRunDTO latestRun;

  private List<IssueStageBudgetDTO> stageBudgets;
  private List<IssueAgentThreadDTO> agentThreads;
}
