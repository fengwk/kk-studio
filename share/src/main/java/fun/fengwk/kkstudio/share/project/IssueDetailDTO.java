package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Issue 详情公开聚合 DTO。
 *
 * <p>包含当前要求、{@code BLOCKED} 标记、依赖边、稳定 Agent 归属、有界 Activity 窗口、公开证据与历史 Runs。 {@code activities}
 * 只返回有界窗口（调用方可用 {@code afterSequence}/{@code limit} 分页），{@code nextActivityCursor} 非空表示其后仍有
 * Activity；{@code evidence} 同样是有界窗口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueDetailDTO {

  private IssueDTO issue;
  private Boolean blocked;
  private List<IssueDependencyDTO> dependencies;
  private List<IssueAgentSessionDTO> sessions;
  private List<IssueActivityDTO> activities;

  /** Issue 已发布证据（有界窗口，按发布时间倒序，最多 {@code IssueEvidenceService.MAX_EVIDENCE_LIMIT} 条）。 */
  private List<IssueEvidenceDTO> evidence;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String nextActivityCursor;

  private List<IssueRunDTO> runs;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private IssueRunDTO currentRun;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private IssueRunDTO latestRun;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
