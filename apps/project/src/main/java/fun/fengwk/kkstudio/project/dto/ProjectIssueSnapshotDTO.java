package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 项目快照中的单个 Issue 投影。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectIssueSnapshotDTO {

  private IssueDTO issue;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable IssueRunSummaryDTO currentOrLatestRun;
}
