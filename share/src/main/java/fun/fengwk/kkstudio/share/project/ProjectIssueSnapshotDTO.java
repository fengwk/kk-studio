package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Project Snapshot 中的 Issue 投影条目。包含 issue 实体、blocked 状态和 currentOrLatestRun 摘要。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectIssueSnapshotDTO {

  private IssueDTO issue;
  private Boolean blocked;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private IssueRunSummaryDTO currentOrLatestRun;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
