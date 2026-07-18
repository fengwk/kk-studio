package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/** 有序、exactly-once 应用到 Entry 的 Thread 输入。 */
public record ThreadInput(
    long id,
    long threadId,
    long sequence,
    ThreadInputType inputType,
    String payloadJson,
    String clientMessageId,
    Long appliedEntryId,
    Instant appliedAt,
    Instant createdAt) {

  public ThreadInput {
    if (id <= 0 || threadId <= 0) {
      throw new IllegalArgumentException("input and thread ids must be positive");
    }
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    inputType = Objects.requireNonNull(inputType, "inputType");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    if (payloadJson.isBlank()) {
      throw new IllegalArgumentException("payloadJson must not be blank");
    }
    if (clientMessageId != null && clientMessageId.isBlank()) {
      throw new IllegalArgumentException("clientMessageId must not be blank when present");
    }
    if (appliedEntryId != null && appliedEntryId <= 0) {
      throw new IllegalArgumentException("appliedEntryId must be positive when present");
    }
    if ((appliedEntryId == null) != (appliedAt == null)) {
      throw new IllegalArgumentException(
          "appliedEntryId and appliedAt must both be set or both null");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public boolean applied() {
    return appliedEntryId != null;
  }
}
