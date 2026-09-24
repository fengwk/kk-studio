package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Project Snapshot 中的 Issue 投影条目。包含 issue 实体、blocked 状态、当前审查窗口内的打回次数和 currentOrLatestRun 摘要。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectIssueSnapshotDTO {

  private IssueDTO issue;
  private Boolean blocked;

  /**
   * 当前审查窗口内的审查打回次数，以 canonical non-negative decimal string 传输。
   *
   * <p>与 {@link IssueDTO} 中需要由事实流推导的字段不同，该值由服务端权威聚合直接给出（含审查窗口重置与恢复语义），供 BLOCKED 卡片展示 {@code current
   * / project.maxReviewRejections}，客户端不得自行推导。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String reviewRejectionCount;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private IssueRunSummaryDTO currentOrLatestRun;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
