package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 不可变的 durable Thread mailbox command aggregate。
 *
 * <p>身份为 {@code (threadId, sequence)}，不携带 surrogate id。生命周期 state 由两个 nullable terminal marker
 * 派生；不存储 status 或 appliedAt 投影。{@code requestHash} 是客户端 raw 命令（含 ordered contents 与 uploadId）的
 * canonical SHA-256，与 {@code clientCommandId} 一起构成幂等键：同 id + 同 hash 精确重放，同 id + 不同 hash 冲突。
 */
public record ThreadCommand(
    UUID threadId,
    long sequence,
    ThreadCommandPayload payload,
    UUID clientCommandId,
    String requestHash,
    UUID consumedTurnStartEntryId,
    Instant cancelledAt,
    Instant createdAt) {

  private static final Pattern REQUEST_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public ThreadCommand {
    Objects.requireNonNull(threadId, "threadId");
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    payload = Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(clientCommandId, "clientCommandId");
    if (requestHash == null || !REQUEST_HASH_PATTERN.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("requestHash must be 64 lowercase hexadecimal characters");
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
   * command 可被 consume；{@code turnStartEntryId} 不得为 null。
   */
  public ThreadCommand consume(UUID turnStartEntryId) {
    if (state() != ThreadCommandState.QUEUED) {
      throw new IllegalStateException("only QUEUED commands can be consumed");
    }
    Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        clientCommandId,
        requestHash,
        turnStartEntryId,
        null,
        createdAt);
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
        threadId, sequence, payload, clientCommandId, requestHash, null, cancelledAt, createdAt);
  }
}
