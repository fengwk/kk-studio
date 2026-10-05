package fun.fengwk.kkstudio.harness.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Stop 在单个 Thread 上留下的持久回执。
 *
 * <p>回执记录该节点自己的停止边界与被取消的命令数量；{@code cancelledInputs} 只含人工 {@code USER_MESSAGE} / {@code GOAL}
 * 且按 sequence 升序，供前端恢复草稿。回执身份 {@code (threadId, stopRequestId)} 不可重用：重放旧 stopRequestId 返回同一回执，
 * 不影响后来启动的新工作。
 */
public record StoppedThreadReceipt(
    UUID threadId,
    UUID stopRequestId,
    UUID stoppedTurnEndEntryId,
    int cancelledCommandCount,
    List<CancelledThreadInput> cancelledInputs) {

  public StoppedThreadReceipt {
    threadId = Objects.requireNonNull(threadId, "threadId");
    stopRequestId = Objects.requireNonNull(stopRequestId, "stopRequestId");
    if (cancelledCommandCount < 0) {
      throw new IllegalArgumentException("cancelledCommandCount must not be negative");
    }
    cancelledInputs = List.copyOf(Objects.requireNonNull(cancelledInputs, "cancelledInputs"));
    long previous = 0L;
    for (CancelledThreadInput input : cancelledInputs) {
      if (input.sequence() <= previous) {
        throw new IllegalArgumentException(
            "cancelledInputs sequences must be strictly increasing");
      }
      previous = input.sequence();
    }
    if (cancelledInputs.size() > cancelledCommandCount) {
      throw new IllegalArgumentException(
          "cancelledInputs count must not exceed cancelledCommandCount");
    }
  }
}
