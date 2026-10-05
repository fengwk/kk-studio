package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Stop 退回到草稿的一条人类输入。
 *
 * <p>只含人工 {@code USER_MESSAGE} / {@code GOAL}，按 sequence 升序；{@code CUSTOM_MESSAGE} 与 {@code NOTIFICATION}
 * 不退草稿。payload 为原始 command payload，草稿按 payload 还原。
 */
public record CancelledThreadInput(long sequence, UUID idempotencyKey, ThreadCommandPayload payload) {

  public CancelledThreadInput {
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    payload = Objects.requireNonNull(payload, "payload");
  }
}
