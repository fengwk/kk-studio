package fun.fengwk.kkstudio.harness.kernel.thread;

import fun.fengwk.kkstudio.harness.kernel.execution.Lease;

import java.time.Instant;
import java.util.Objects;

/**
 * Entry Tree 上某个 head 的 durable actor 控制面。
 *
 * <p>processorLease 为空不等于 Thread 空闲。Stop 通过递增 executionEpoch 隔离旧执行。
 */
public record HarnessThread(
    long id,
    long sessionId,
    long headEntryId,
    long inputSequence,
    boolean runnable,
    long executionEpoch,
    Lease processorLease,
    Instant createdAt,
    Instant updatedAt) {

  public HarnessThread {
    if (id <= 0) {
      throw new IllegalArgumentException("thread id must be positive");
    }
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (headEntryId <= 0) {
      throw new IllegalArgumentException("headEntryId must be positive");
    }
    if (inputSequence < 0) {
      throw new IllegalArgumentException("inputSequence must not be negative");
    }
    if (executionEpoch < 0) {
      throw new IllegalArgumentException("executionEpoch must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * 判断在指定观察时刻是否存在有效 processor lease。
   *
   * @param observedAt 观察时刻，必须非 null
   * @return lease 存在且未过期
   * @throws NullPointerException 当 observedAt 为 null 时
   */
  public boolean hasActiveProcessorAt(Instant observedAt) {
    Objects.requireNonNull(observedAt, "observedAt");
    return processorLease != null && processorLease.isActiveAt(observedAt);
  }
}
