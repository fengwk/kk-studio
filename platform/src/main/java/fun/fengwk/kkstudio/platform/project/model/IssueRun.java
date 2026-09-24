package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * IssueRun：业务给 Agent 的一次有界指派。
 *
 * <p>Run 状态只描述执行；SUBMITTED/APPROVED/CHANGES_REQUESTED 是带来源的业务结果。同一 (Issue, Agent) 的后续 Run 复用自己的
 * Session 与工作 Branch，Run 每次新建。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRun {

  private UUID id;
  private UUID issueId;
  private long ordinal;
  private IssueRunRole role;

  /** 冻结的 Agent 身份（Issue 参与者身份，不是模型名）。 */
  private String agentName;

  /** REVIEWER 必填，指向被审查的 EXECUTOR Run。 */
  private UUID submissionRunId;

  private IssueRunStatus status;
  private IssueRunOutcome outcome;

  /** 已投递给本 Run 的 Activity sequence 游标。 */
  private long observedActivitySequence;

  private int continuationCount;
  private int maxContinuations;
  private Instant deadline;
  private String waitingReason;
  private String result;
  private String terminalActionId;
  private long version;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant completedAt;

  public boolean isActive() {
    return status != null && status.isActive();
  }

  public boolean isTerminal() {
    return status != null && status.isTerminal();
  }

  public boolean isExecutor() {
    return role == IssueRunRole.EXECUTOR;
  }

  public boolean isReviewer() {
    return role == IssueRunRole.REVIEWER;
  }
}
