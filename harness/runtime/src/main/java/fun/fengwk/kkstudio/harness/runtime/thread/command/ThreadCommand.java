package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 不可变的 durable Thread mailbox command aggregate。
 *
 * <p>生命周期 state 由两个 nullable terminal marker 派生；不存储 status 或 appliedAt 投影。
 */
public record ThreadCommand(
    long id,
    long threadId,
    long sequence,
    ThreadCommandPayload payload,
    String clientCommandId,
    Long consumedTurnStartEntryId,
    Instant cancelledAt,
    Instant createdAt) {

  public ThreadCommand {
    if (id <= 0) {
      throw new IllegalArgumentException("command id must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    payload = Objects.requireNonNull(payload, "payload");
    clientCommandId =
        CommandValueValidation.requireCanonicalName(clientCommandId, "clientCommandId");
    if (consumedTurnStartEntryId != null && consumedTurnStartEntryId <= 0) {
      throw new IllegalArgumentException("consumedTurnStartEntryId must be positive");
    }
    if (consumedTurnStartEntryId != null && cancelledAt != null) {
      throw new IllegalArgumentException(
          "consumedTurnStartEntryId and cancelledAt must not both be present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (cancelledAt != null && cancelledAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("cancelledAt must not precede createdAt");
    }
  }

  /** 从 durable marker 派生 QUEUED/APPLIED/CANCELLED。 */
  public ThreadCommandState state() {
    if (consumedTurnStartEntryId != null) {
      return ThreadCommandState.APPLIED;
    }
    if (cancelledAt != null) {
      return ThreadCommandState.CANCELLED;
    }
    return ThreadCommandState.QUEUED;
  }

  /** 返回 typed command kind，不存储重复的 discriminator。 */
  public ThreadCommandType type() {
    return payload.type();
  }

  /**
   * 纯 QUEUED -&gt; APPLIED 迁移：附加消费该 command 的 TURN_START Entry id 并清空 cancel marker。 只有 QUEUED 状态的
   * command 可被 consume；{@code turnStartEntryId} 必须为正。
   */
  public ThreadCommand consume(long turnStartEntryId) {
    if (state() != ThreadCommandState.QUEUED) {
      throw new IllegalStateException("only QUEUED commands can be consumed");
    }
    if (turnStartEntryId <= 0) {
      throw new IllegalArgumentException("turnStartEntryId must be positive");
    }
    return new ThreadCommand(
        id, threadId, sequence, payload, clientCommandId, turnStartEntryId, null, createdAt);
  }

  /**
   * 纯 QUEUED -&gt; CANCELLED 迁移：附加取消时间并清空 consumed marker。只有 QUEUED 状态的 command 可被 cancel；{@code
   * cancelledAt} 不得早于 command 的创建时间。
   */
  public ThreadCommand cancel(Instant cancelledAt) {
    if (state() != ThreadCommandState.QUEUED) {
      throw new IllegalStateException("only QUEUED commands can be cancelled");
    }
    Objects.requireNonNull(cancelledAt, "cancelledAt");
    if (cancelledAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("cancelledAt must not precede createdAt");
    }
    return new ThreadCommand(
        id, threadId, sequence, payload, clientCommandId, null, cancelledAt, createdAt);
  }
}
