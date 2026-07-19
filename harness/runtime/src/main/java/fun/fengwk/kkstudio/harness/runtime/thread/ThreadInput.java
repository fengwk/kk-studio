package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/** 有序、网络幂等的 Thread mailbox 输入。 */
public record ThreadInput(
    long id,
    long threadId,
    long sequence,
    ThreadInputType inputType,
    String payloadJson,
    String clientMessageId,
    ThreadInputStatus status,
    Long appliedEntryId,
    Instant resolvedAt,
    Long cancelledByStopId,
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
    status = Objects.requireNonNull(status, "status");
    if (appliedEntryId != null && appliedEntryId <= 0) {
      throw new IllegalArgumentException("appliedEntryId must be positive when present");
    }
    if (cancelledByStopId != null && cancelledByStopId <= 0) {
      throw new IllegalArgumentException("cancelledByStopId must be positive when present");
    }
    switch (status) {
      case QUEUED -> {
        if (appliedEntryId != null || resolvedAt != null || cancelledByStopId != null) {
          throw new IllegalArgumentException("queued input must not carry resolution fields");
        }
      }
      case APPLIED -> {
        if (appliedEntryId == null || resolvedAt == null) {
          throw new IllegalArgumentException(
              "applied input requires appliedEntryId and resolvedAt");
        }
        if (cancelledByStopId != null) {
          throw new IllegalArgumentException("applied input must not carry cancelledByStopId");
        }
      }
      case CANCELLED -> {
        if (cancelledByStopId == null || resolvedAt == null) {
          throw new IllegalArgumentException(
              "cancelled input requires cancelledByStopId and resolvedAt");
        }
        if (appliedEntryId != null) {
          throw new IllegalArgumentException("cancelled input must not carry appliedEntryId");
        }
      }
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public boolean queued() {
    return status == ThreadInputStatus.QUEUED;
  }

  public boolean applied() {
    return status == ThreadInputStatus.APPLIED;
  }

  public boolean cancelled() {
    return status == ThreadInputStatus.CANCELLED;
  }
}
