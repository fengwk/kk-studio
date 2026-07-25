package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/**
 * 有序、幂等的 Thread mailbox 输入。
 *
 * <p>{@code idempotencyKey} 是稳定客户端引用键，用于网络重试下的去重；{@code appliedAt} 的语义由 {@link InputStatus}
 * 决定：{@code APPLIED} 时必须非空且不得早于 {@code createdAt}，{@code QUEUED} 与 {@code CANCELLED} 时必须为 null。
 */
public record ThreadInput(
    long id,
    long threadId,
    long sequence,
    ThreadInputType type,
    ThreadInputPayload payload,
    String idempotencyKey,
    InputStatus status,
    Instant createdAt,
    Instant appliedAt) {

  public ThreadInput {
    if (id <= 0) {
      throw new IllegalArgumentException("input id must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    type = Objects.requireNonNull(type, "type");
    payload = Objects.requireNonNull(payload, "payload");
    if (type != payload.type()) {
      throw new IllegalArgumentException("input type does not match payload type");
    }
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    status = Objects.requireNonNull(status, "status");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (status == InputStatus.APPLIED) {
      Objects.requireNonNull(appliedAt, "appliedAt");
      if (appliedAt.isBefore(createdAt)) {
        throw new IllegalArgumentException("appliedAt must not precede createdAt");
      }
    } else if (appliedAt != null) {
      throw new IllegalArgumentException("non-APPLIED input must not carry appliedAt");
    }
  }

  /** 该 Input 是否仍处于 mailbox 队列。 */
  public boolean isQueued() {
    return status == InputStatus.QUEUED;
  }

  /** 该 Input 是否已进入 terminal（APPLIED 或 CANCELLED）。 */
  public boolean isTerminal() {
    return status.isTerminal();
  }
}
