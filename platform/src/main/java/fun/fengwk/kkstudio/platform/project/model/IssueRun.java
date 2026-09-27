package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code project_issue_run} 行：冻结自己的 Issue/state/Session/Thread 坐标与历史区间。
 *
 * <p>状态语义复用领域 {@link IssueRunStatus}：RUNNING/WAITING 是活动状态且无终态区间，终态必须冻结 {@code endEntryId/endedAt}，
 * FAILED/UNKNOWN 必须给出原因。{@code version} 是内部回调/收尾 CAS，不是用户配置版本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRun {

  private UUID id;
  private UUID issueId;
  private long ordinal;
  private String state;
  private UUID sessionId;
  private UUID threadId;
  private IssueRunStatus status;
  private UUID startEntryId;
  private UUID endEntryId;
  private UUID finalAnswerEntryId;
  private String nextState;
  private long observedActivitySequence;
  private long remainingExecutionMs;
  private Instant activeSince;
  private String error;
  private long version;
  private Instant startedAt;
  private Instant endedAt;

  public boolean isActive() {
    return status != null && status.isActive();
  }

  public boolean isTerminal() {
    return status != null && status.isTerminal();
  }
}
