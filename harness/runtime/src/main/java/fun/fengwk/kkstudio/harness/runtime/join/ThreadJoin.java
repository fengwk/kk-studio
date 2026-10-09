package fun.fengwk.kkstudio.harness.runtime.join;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次源命令接受后的不可变 join 凭据。执行终止边界冻结一次结果；父交付引用其 command sequence，不能从后续的子 Thread head 重新推导旧结果。
 *
 * <p>{@code terminalEntryId} 是本次执行终止时冻结的 terminal Entry（首次最终回答、不可继续失败/Stop 的收尾）；{@code
 * finalAnswerEntryId} 是可空的最终回答入口，绝不借用源输入应用之前的回答。{@code matched()} 由 {@code terminalEntryId} 判定。
 *
 * <p>{@code purpose} 区分用户可见 task 与内部压缩；{@code supersededByInvocationId} 是同一父/子对上后续续接 join 的
 * invocation identity：同一父子对至多有一个有效未完成 join，旧 join 被新续接原子 supersede 后保留原 invocation identity 与排队
 * prompt，但不再占用 活跃额度、不再产生重复交付。已匹配结果不可变，也绝不被 supersede。
 */
public record ThreadJoin(
    UUID invocationId,
    String requestHash,
    UUID parentThreadId,
    UUID childThreadId,
    long sourceCommandSequence,
    String agent,
    Integer maxTurns,
    long reminderTurn,
    UUID terminalEntryId,
    UUID finalAnswerEntryId,
    Long deliveryCommandSequence,
    Instant createdAt,
    Instant updatedAt,
    JoinPurpose purpose,
    UUID supersededByInvocationId) {

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public ThreadJoin {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(purpose, "purpose");
    if (requestHash == null || !HASH.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 hex string");
    }
    if (parentThreadId != null && parentThreadId.equals(childThreadId)) {
      throw new IllegalArgumentException("join parent and child must differ");
    }
    if (sourceCommandSequence <= 0 || reminderTurn < 0) {
      throw new IllegalArgumentException("invalid join command/reminder");
    }
    if (agent == null || agent.isBlank() || agent.length() > 256) {
      throw new IllegalArgumentException("agent must be nonblank and at most 256 characters");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    if (finalAnswerEntryId != null && terminalEntryId == null) {
      throw new IllegalArgumentException("a final answer entry requires a frozen terminal entry");
    }
    if (deliveryCommandSequence != null
        && (parentThreadId == null || terminalEntryId == null || deliveryCommandSequence <= 0)) {
      throw new IllegalArgumentException("delivery requires a matched parent join");
    }
    if (supersededByInvocationId != null) {
      if (supersededByInvocationId.equals(invocationId)) {
        throw new IllegalArgumentException("a join cannot supersede itself");
      }
      if (terminalEntryId != null) {
        throw new IllegalArgumentException("a matched join cannot be superseded");
      }
    }
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt precedes createdAt");
    }
  }

  public boolean matched() {
    return terminalEntryId != null;
  }

  /** 冻结一次结果：{@code terminalEntryId} 必填，{@code finalAnswerEntryId} 可空。已匹配的 join 不可再次冻结。 */
  public ThreadJoin match(UUID terminalEntryId, UUID finalAnswerEntryId, Instant now) {
    if (matched()) {
      throw new IllegalArgumentException("join already matched");
    }
    if (supersededByInvocationId != null) {
      throw new IllegalArgumentException("a superseded join cannot be matched");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        agent,
        maxTurns,
        reminderTurn,
        Objects.requireNonNull(terminalEntryId, "terminalEntryId"),
        finalAnswerEntryId,
        null,
        createdAt,
        now,
        purpose,
        null);
  }

  public ThreadJoin delivered(long sequence, Instant now) {
    if (!matched() || deliveryCommandSequence != null) {
      throw new IllegalArgumentException("join not pending delivery");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        agent,
        maxTurns,
        reminderTurn,
        terminalEntryId,
        finalAnswerEntryId,
        sequence,
        createdAt,
        now,
        purpose,
        supersededByInvocationId);
  }

  public ThreadJoin remind(long turn, Instant now) {
    if (matched() || turn <= reminderTurn) {
      throw new IllegalArgumentException("reminder must advance an unmatched join");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        agent,
        maxTurns,
        turn,
        terminalEntryId,
        finalAnswerEntryId,
        deliveryCommandSequence,
        createdAt,
        now,
        purpose,
        supersededByInvocationId);
  }

  /**
   * 用同一父/子对上的后续续接 join 原子 supersede 本次未完成 join：只记录接管它的 invocation identity，保留源命令（排队 prompt）与本次
   * invocation identity；已匹配或已 supersede 的 join 不可再次 supersede。
   */
  public ThreadJoin superseded(UUID successorInvocationId, Instant now) {
    Objects.requireNonNull(successorInvocationId, "successorInvocationId");
    if (matched()) {
      throw new IllegalArgumentException("a matched join cannot be superseded");
    }
    if (supersededByInvocationId != null) {
      throw new IllegalArgumentException("join already superseded");
    }
    if (successorInvocationId.equals(invocationId)) {
      throw new IllegalArgumentException("a join cannot supersede itself");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        agent,
        maxTurns,
        reminderTurn,
        terminalEntryId,
        finalAnswerEntryId,
        deliveryCommandSequence,
        createdAt,
        now,
        purpose,
        successorInvocationId);
  }

  public static void validateTransition(ThreadJoin old, ThreadJoin next) {
    Objects.requireNonNull(old, "old");
    Objects.requireNonNull(next, "next");
    if (!old.invocationId.equals(next.invocationId)
        || !old.requestHash.equals(next.requestHash)
        || !Objects.equals(old.parentThreadId, next.parentThreadId)
        || !old.childThreadId.equals(next.childThreadId)
        || old.sourceCommandSequence != next.sourceCommandSequence
        || !old.agent.equals(next.agent)
        || !Objects.equals(old.maxTurns, next.maxTurns)
        || !old.createdAt.equals(next.createdAt)
        || next.updatedAt.isBefore(old.updatedAt)
        || next.reminderTurn < old.reminderTurn
        || old.purpose != next.purpose
        || (old.matched()
            && (!Objects.equals(old.terminalEntryId, next.terminalEntryId)
                || !Objects.equals(old.finalAnswerEntryId, next.finalAnswerEntryId)))
        || (old.deliveryCommandSequence != null
            && !old.deliveryCommandSequence.equals(next.deliveryCommandSequence))
        || (old.matched() && next.reminderTurn != old.reminderTurn)
        || (old.supersededByInvocationId != null
            && !old.supersededByInvocationId.equals(next.supersededByInvocationId))) {
      throw new IllegalArgumentException("join durable identity or receipt cannot change");
    }
  }
}
