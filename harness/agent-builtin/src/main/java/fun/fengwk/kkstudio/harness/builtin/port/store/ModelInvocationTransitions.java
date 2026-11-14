package fun.fengwk.kkstudio.harness.builtin.port.store;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ModelInvocation} 的一次状态转换校验：identity 与 request 不可变、attempt 只在 READY-&gt;RUNNING 时
 * +1、terminal 事实不可变、 checkpoint / failedAttempts / 组进度 / requestedAt 各自的合法变化。
 *
 * <p>由 {@link ModelInvocation#validateTransition(ModelInvocation, ModelInvocation)} 转调；Store 必须在每次
 * {@code update*} 写入前调用它。只做判断，不修改任何输入。
 */
final class ModelInvocationTransitions {

  private ModelInvocationTransitions() {}

  static void validate(ModelInvocation stored, ModelInvocation next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    requireStableIdentity(stored, next);
    int attemptDelta = next.attempt() - stored.attempt();
    if (attemptDelta < 0) {
      throw new IllegalArgumentException("attempt must not regress");
    }
    requireLegalStatusMove(stored.status(), next.status());
    requireAttemptDelta(stored, next, attemptDelta);
    if (stored.status().isTerminal()) {
      requireTerminalImmutability(stored, next);
    }
    requireCheckpointTransition(stored, next);
    requireFailedAttemptsTransition(stored, next);
    requireGroupProgressTransition(stored, next);
    requireRequestedAtTransition(stored, next);
  }

  /** 每次确认启动都记录该次真实请求起始时间；其他转换不得改写。 */
  private static void requireRequestedAtTransition(ModelInvocation stored, ModelInvocation next) {
    if (Objects.equals(stored.requestedAt(), next.requestedAt())) {
      return;
    }
    if (next.requestedAt() == null
        || next.status() != ModelInvocationStatus.RUNNING
        || stored.status() != ModelInvocationStatus.READY) {
      throw new IllegalArgumentException("requestedAt may only change on a READY -> RUNNING start");
    }
  }

  /** 组进度只能单调推进；跨成员时成员内尝试数归零，换配置版本绝不允许。 */
  private static void requireGroupProgressTransition(ModelInvocation stored, ModelInvocation next) {
    ModelGroupProgress before = stored.groupProgress();
    ModelGroupProgress after = next.groupProgress();
    if (Objects.equals(before, after)) {
      return;
    }
    ModelGroupExecution group = stored.requestSpec().groupExecution();
    if (group == null || before == null || after == null) {
      throw new IllegalArgumentException("group progress cannot be introduced or removed");
    }
    if (next.status() != ModelInvocationStatus.READY) {
      throw new IllegalArgumentException(
          "group progress may only change on a READY retry or before the start");
    }
    if (!before.groupName().equals(after.groupName())
        || before.groupVersion() != after.groupVersion()
        || !after.groupName().equals(group.groupName())
        || after.groupVersion() != group.groupVersion()) {
      throw new IllegalArgumentException("group progress must stay on the frozen group version");
    }
    if (after.memberIndex() < before.memberIndex()) {
      throw new IllegalArgumentException("group member index must not regress");
    }
    if (after.memberIndex() == before.memberIndex()) {
      if (after.memberAttempts() < before.memberAttempts()) {
        throw new IllegalArgumentException("member attempts must not regress");
      }
    } else if (after.memberAttempts() != 0) {
      throw new IllegalArgumentException("a new group member must start with zero member attempts");
    }
  }

  private static void requireStableIdentity(ModelInvocation stored, ModelInvocation next) {
    if (!stored.id().equals(next.id())
        || !stored.threadId().equals(next.threadId())
        || !stored.turnStartEntryId().equals(next.turnStartEntryId())
        || !stored.requestHeadEntryId().equals(next.requestHeadEntryId())
        || !stored.requestSpec().equals(next.requestSpec())
        || !stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException(
          "model invocation identity"
              + " (id/thread/turnStartEntry/requestHeadEntry/requestSpec/createdAt) must not change");
    }
  }

  private static void requireLegalStatusMove(
      ModelInvocationStatus stored, ModelInvocationStatus next) {
    boolean allowed =
        switch (stored) {
          case READY -> next == ModelInvocationStatus.READY
              || next == ModelInvocationStatus.RUNNING
              || next == ModelInvocationStatus.FAILED
              || next == ModelInvocationStatus.CANCELLED;
          case RUNNING -> next == ModelInvocationStatus.RUNNING
              || next == ModelInvocationStatus.READY
              || next == ModelInvocationStatus.SUCCEEDED
              || next == ModelInvocationStatus.FAILED
              || next == ModelInvocationStatus.CANCELLED
              || next == ModelInvocationStatus.UNKNOWN;
          case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> next == stored;
        };
    if (!allowed) {
      throw new IllegalArgumentException(
          "illegal model invocation status transition " + stored + " -> " + next);
    }
  }

  private static void requireAttemptDelta(
      ModelInvocation stored, ModelInvocation next, int attemptDelta) {
    if (stored.status() == ModelInvocationStatus.READY
        && next.status() == ModelInvocationStatus.RUNNING) {
      if (attemptDelta != 1) {
        throw new IllegalArgumentException("READY -> RUNNING must advance attempt by exactly one");
      }
      return;
    }
    if (attemptDelta != 0) {
      throw new IllegalArgumentException("attempt must not change on this transition");
    }
  }

  private static void requireTerminalImmutability(ModelInvocation stored, ModelInvocation next) {
    if (stored.status() != next.status()
        || stored.attempt() != next.attempt()
        || !Objects.equals(stored.result(), next.result())
        || !Objects.equals(stored.error(), next.error())) {
      throw new IllegalArgumentException("terminal model invocation facts must not change");
    }
    UUID storedResultEntryId = stored.resultEntryId();
    UUID nextResultEntryId = next.resultEntryId();
    if (storedResultEntryId == null) {
      return;
    }
    if (!Objects.equals(storedResultEntryId, nextResultEntryId)) {
      throw new IllegalArgumentException("terminal resultEntryId must not change");
    }
  }

  /**
   * checkpoint 仅能在 RUNNING-&gt;RUNNING 或 RUNNING-&gt;terminal 转换时被引入或增长。进入 terminal 状态或以 READY 重试时，
   * 可以保留完全相同的 stored checkpoint 或清除它；在 terminal 内部不能再引入、增长或分叉 checkpoint。
   */
  private static void requireCheckpointTransition(ModelInvocation stored, ModelInvocation next) {
    StreamCheckpoint storedCheckpoint = stored.streamCheckpoint();
    StreamCheckpoint nextCheckpoint = next.streamCheckpoint();
    boolean growthAllowed =
        stored.status() == ModelInvocationStatus.RUNNING
            && (next.status() == ModelInvocationStatus.RUNNING || next.status().isTerminal());
    if (storedCheckpoint == null) {
      requireCheckpointIntroduction(nextCheckpoint, growthAllowed);
      return;
    }
    if (nextCheckpoint == null) {
      requireCheckpointClearance(next.status());
      return;
    }
    if (!growthAllowed) {
      requireExactCheckpointReplay(storedCheckpoint, nextCheckpoint);
      return;
    }
    requireCheckpointGrowth(storedCheckpoint, nextCheckpoint);
  }

  private static void requireCheckpointIntroduction(
      StreamCheckpoint nextCheckpoint, boolean growthAllowed) {
    if (nextCheckpoint != null && !growthAllowed) {
      throw new IllegalArgumentException(
          "a checkpoint may only be introduced on a RUNNING -> RUNNING or RUNNING -> terminal"
              + " transition");
    }
  }

  private static void requireCheckpointClearance(ModelInvocationStatus nextStatus) {
    boolean clearingAllowed = nextStatus.isTerminal() || nextStatus == ModelInvocationStatus.READY;
    if (!clearingAllowed) {
      throw new IllegalArgumentException(
          "checkpoint may only be cleared when entering a terminal state or retrying as READY");
    }
  }

  private static void requireExactCheckpointReplay(
      StreamCheckpoint storedCheckpoint, StreamCheckpoint nextCheckpoint) {
    if (!nextCheckpoint.equals(storedCheckpoint)) {
      throw new IllegalArgumentException(
          "a transition away from RUNNING or within a terminal state may only keep the exact"
              + " stored checkpoint or clear it");
    }
  }

  /** 同一 attempt 内 sequence 只增不减，同序号必须完全重放，增长时 text / thinking 保持前缀且至少一方增长。 */
  private static void requireCheckpointGrowth(
      StreamCheckpoint storedCheckpoint, StreamCheckpoint nextCheckpoint) {
    if (nextCheckpoint.attempt() != storedCheckpoint.attempt()) {
      throw new IllegalArgumentException("checkpoint must stay on the same attempt");
    }
    if (nextCheckpoint.sequence() < storedCheckpoint.sequence()) {
      throw new IllegalArgumentException("checkpoint sequence must not regress");
    }
    if (nextCheckpoint.sequence() == storedCheckpoint.sequence()) {
      if (!nextCheckpoint.equals(storedCheckpoint)) {
        throw new IllegalArgumentException("same checkpoint sequence requires exact replay");
      }
      return;
    }
    if (!nextCheckpoint.text().startsWith(storedCheckpoint.text())
        || !nextCheckpoint.thinking().startsWith(storedCheckpoint.thinking())) {
      throw new IllegalArgumentException(
          "a larger checkpoint sequence must keep text and thinking as strict prefixes");
    }
    boolean textGrew = nextCheckpoint.text().length() > storedCheckpoint.text().length();
    boolean thinkingGrew =
        nextCheckpoint.thinking().length() > storedCheckpoint.thinking().length();
    if (!textGrew && !thinkingGrew) {
      throw new IllegalArgumentException(
          "a larger checkpoint sequence requires strict growth in text or thinking");
    }
  }

  /**
   * failedAttempts 是 append-only 的失败 attempt 审计：除「链接结果 Entry 时清空」与「重试 / 组降级 / 组耗尽」三种转换外必须保持不变；
   * 追加时旧前缀逐条相等，且追加项恰为本次消耗的 attempt。
   */
  private static void requireFailedAttemptsTransition(
      ModelInvocation stored, ModelInvocation next) {
    List<ModelAttemptFailure> storedFailures = stored.failedAttempts();
    List<ModelAttemptFailure> nextFailures = next.failedAttempts();
    if (linksResultEntry(stored, next)) {
      if (!nextFailures.isEmpty()) {
        throw new IllegalArgumentException(
            "linking a terminal result entry must clear materialized failedAttempts");
      }
      return;
    }
    boolean confirmedRetry =
        stored.status() == ModelInvocationStatus.RUNNING
            && next.status() == ModelInvocationStatus.READY;
    boolean groupAdvance =
        confirmedRetry
            && stored.groupProgress() != null
            && next.groupProgress() != null
            && next.groupProgress().memberIndex() > stored.groupProgress().memberIndex();
    // 组成员全部耗尽的终止失败追加最后一次真实失败审计；专用终止（取消、上下文溢出）保持审计不变。
    boolean groupExhausted =
        stored.status() == ModelInvocationStatus.RUNNING
            && next.status() == ModelInvocationStatus.FAILED
            && stored.groupProgress() != null
            && nextFailures.size() != storedFailures.size();
    if (!confirmedRetry && !groupExhausted) {
      if (!storedFailures.equals(nextFailures)) {
        throw new IllegalArgumentException("failedAttempts may only change on a retry transition");
      }
      return;
    }
    requireAppendedFailure(stored, nextFailures, storedFailures);
    ModelAttemptFailure appended = nextFailures.get(nextFailures.size() - 1);
    if (!appended.error().retryCandidate() && !groupAdvance && !groupExhausted) {
      throw new IllegalArgumentException("failed attempt requires a retryable error");
    }
  }

  private static boolean linksResultEntry(ModelInvocation stored, ModelInvocation next) {
    return stored.resultEntryId() == null && next.resultEntryId() != null;
  }

  private static void requireAppendedFailure(
      ModelInvocation stored,
      List<ModelAttemptFailure> nextFailures,
      List<ModelAttemptFailure> storedFailures) {
    if (nextFailures.size() != storedFailures.size() + 1) {
      throw new IllegalArgumentException(
          "a retry transition must append exactly one failed attempt");
    }
    for (int index = 0; index < storedFailures.size(); index++) {
      if (!storedFailures.get(index).equals(nextFailures.get(index))) {
        throw new IllegalArgumentException("failedAttempts is append-only");
      }
    }
    ModelAttemptFailure appended = nextFailures.get(nextFailures.size() - 1);
    if (appended.attempt() != stored.attempt()) {
      throw new IllegalArgumentException(
          "failed attempt must equal the attempt consumed by this retry");
    }
  }
}
