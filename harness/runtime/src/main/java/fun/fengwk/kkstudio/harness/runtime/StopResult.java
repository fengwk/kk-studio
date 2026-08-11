package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变 Stop 结果。
 *
 * <p>{@link Status#IDLE} 表示未创建被停止的 Turn，但 {@code cancelledCommandCount} 仍可能为正。 {@link
 * Status#REPLAYED} 标识先前已停止的 TURN_END，而返回的 Thread 可能已指向更新的 Turn。
 */
public record StopResult(
    Status status, ThreadState thread, UUID stoppedTurnEndEntryId, int cancelledCommandCount) {

  public StopResult {
    status = Objects.requireNonNull(status, "status");
    thread = Objects.requireNonNull(thread, "thread");
    if (cancelledCommandCount < 0) {
      throw new IllegalArgumentException("cancelledCommandCount must not be negative");
    }
    if (status == Status.IDLE) {
      if (stoppedTurnEndEntryId != null) {
        throw new IllegalArgumentException("IDLE must not carry a stopped TURN_END id");
      }
    } else if (stoppedTurnEndEntryId == null) {
      throw new IllegalArgumentException("STOPPED/REPLAYED require a stopped TURN_END id");
    }
    if (status == Status.REPLAYED && cancelledCommandCount != 0) {
      throw new IllegalArgumentException("REPLAYED must not cancel commands");
    }
  }

  /** 本次调用是停止了一个 Turn、未存在 live Turn，还是重放了一次先前的 Stop。 */
  public enum Status {
    STOPPED,
    IDLE,
    REPLAYED
  }
}
