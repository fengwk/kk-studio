package fun.fengwk.kkstudio.project.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Issue Run 的不可变快照：冻结 Issue/阶段/Session/Thread 坐标、入口区间与收尾事实。
 *
 * <p>Run 只承载本行可判定的事实：活动 Run 保持计时与剩余活动额度且没有结束区间，终态 Run 冻结结束区间与时间， FAILED/UNKNOWN 必须给出原因，COMPLETED
 * 不得带原因，{@code nextState} 只能是非当前阶段、非 BLOCKED 的交接目标。 需要跨行或外部事实的判定（同一 Issue 唯一活动 Run、Entry
 * 父链先后、Thread/Session 归属、Agent 与阶段引用解析） 由持久化约束与 Runtime 事务负责；行版本是持久化 CAS 细节，不属于领域事实。
 */
public record IssueRun(
    UUID id,
    UUID issueId,
    long ordinal,
    ProjectStateCode state,
    UUID sessionId,
    UUID threadId,
    IssueRunStatus status,
    IssueRunEntryBounds entryBounds,
    ProjectStateCode nextState,
    long observedActivitySequence,
    long remainingExecutionMs,
    Instant activeSince,
    String error,
    Instant startedAt,
    Instant endedAt) {

  public IssueRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(entryBounds, "entryBounds");
    Objects.requireNonNull(startedAt, "startedAt");
    if (ordinal < 1) {
      throw new IllegalArgumentException("ordinal must be >= 1");
    }
    if (observedActivitySequence < 0) {
      throw new IllegalArgumentException("observedActivitySequence must be >= 0");
    }
    if (remainingExecutionMs < 0) {
      throw new IllegalArgumentException("remainingExecutionMs must be >= 0");
    }
    if (nextState != null && nextState.equals(state)) {
      throw new IllegalArgumentException("nextState must differ from the run state");
    }
    if (nextState != null && ProjectWorkflowReservedState.BLOCKED.code().equals(nextState)) {
      throw new IllegalArgumentException("BLOCKED cannot be an accepted handoff target");
    }
    if (error != null && error.isBlank()) {
      throw new IllegalArgumentException("error must not be blank when present");
    }
    if (status.isActive()) {
      requireActiveShape(status, entryBounds, remainingExecutionMs, activeSince, error, endedAt);
    } else {
      requireTerminalShape(status, entryBounds, activeSince, error, endedAt, startedAt);
    }
  }

  /** 复验同一 Issue 最多一个活动主 Run；跨行事实的最终保证是数据库部分唯一索引，这里提供事务内前置校验。 */
  public static void requireSingleActiveRunPerIssue(Collection<IssueRun> issueRuns) {
    Objects.requireNonNull(issueRuns, "issueRuns");
    Set<UUID> activeIssueIds = new HashSet<>();
    for (IssueRun run : issueRuns) {
      if (run.status().isActive() && !activeIssueIds.add(run.issueId())) {
        throw new IllegalArgumentException("issue " + run.issueId() + " already has an active run");
      }
    }
  }

  private static void requireActiveShape(
      IssueRunStatus status,
      IssueRunEntryBounds entryBounds,
      long remainingExecutionMs,
      Instant activeSince,
      String error,
      Instant endedAt) {
    if (remainingExecutionMs <= 0) {
      throw new IllegalArgumentException("active run must keep remaining execution budget");
    }
    if (endedAt != null
        || entryBounds.endEntryId() != null
        || entryBounds.finalAnswerEntryId() != null) {
      throw new IllegalArgumentException("active run must not freeze a closed entry interval");
    }
    if (error != null) {
      throw new IllegalArgumentException("active run must not carry an error");
    }
    if (status == IssueRunStatus.RUNNING && activeSince == null) {
      throw new IllegalArgumentException("RUNNING run requires activeSince");
    }
    if (status == IssueRunStatus.WAITING && activeSince != null) {
      throw new IllegalArgumentException("WAITING run must stop activity timing");
    }
  }

  private static void requireTerminalShape(
      IssueRunStatus status,
      IssueRunEntryBounds entryBounds,
      Instant activeSince,
      String error,
      Instant endedAt,
      Instant startedAt) {
    if (activeSince != null) {
      throw new IllegalArgumentException("terminal run must not keep activity timing");
    }
    if (error != null && status == IssueRunStatus.COMPLETED) {
      throw new IllegalArgumentException("COMPLETED run must not carry an error");
    }
    if (endedAt == null || entryBounds.endEntryId() == null) {
      throw new IllegalArgumentException("terminal run must close its entry interval");
    }
    if (endedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("endedAt must not precede startedAt");
    }
    if ((status == IssueRunStatus.FAILED || status == IssueRunStatus.UNKNOWN) && error == null) {
      throw new IllegalArgumentException("FAILED/UNKNOWN run must carry an error");
    }
  }
}
