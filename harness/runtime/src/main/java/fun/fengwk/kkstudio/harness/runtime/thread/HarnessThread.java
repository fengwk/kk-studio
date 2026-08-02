package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.execution.Lease;

import java.time.Instant;
import java.util.Objects;

/**
 * 可在 Session Entry Tree 之间复用的 durable runtime process。
 *
 * <p>Every Thread is created already bound to a Session ROOT entry. processorLease 为空不等于 Thread
 * 空闲；Stop 和 head rebind 通过递增 executionEpoch 隔离旧执行。
 */
public record HarnessThread(
    long id,
    long headEntryId,
    String environmentName,
    long inputSequence,
    boolean runnable,
    long executionEpoch,
    long revision,
    Lease processorLease,
    Instant createdAt,
    Instant updatedAt) {

  public HarnessThread {
    if (id <= 0) {
      throw new IllegalArgumentException("thread id must be positive");
    }
    if (headEntryId <= 0) {
      throw new IllegalArgumentException("headEntryId must be positive");
    }
    environmentName = canonicalEnvironmentName(environmentName);
    if (inputSequence < 0) {
      throw new IllegalArgumentException("inputSequence must not be negative");
    }
    if (executionEpoch < 0) {
      throw new IllegalArgumentException("executionEpoch must not be negative");
    }
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public boolean hasActiveProcessorAt(Instant observedAt) {
    Objects.requireNonNull(observedAt, "observedAt");
    return processorLease != null && processorLease.isActiveAt(observedAt);
  }

  public static String canonicalEnvironmentName(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("environmentName must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException("environmentName must be <= 128 characters");
    }
    return value;
  }
}
