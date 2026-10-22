package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Issue 详情公开聚合 DTO。 */
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

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
