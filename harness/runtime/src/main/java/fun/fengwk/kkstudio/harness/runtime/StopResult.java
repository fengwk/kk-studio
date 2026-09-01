package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变 Stop 结果。
 *
 * <p>{@code replayed=true} 表示重放了一次先前 Stop 的 durable receipt（live receipt：本 Thread 拥有并关闭的 TURN_END 的
 * {@code closeRequestId}；queued-only receipt：本 Thread 上带该 {@code stopRequestId} 的已取消
 * Command），本次调用不写任何 marker、不触碰 version。{@code stoppedTurnEndEntryId} 在停止了一个 live Turn 时非 null；纯
 * queued-only / 未创建 Turn 时（包括 queued-only replay）为 null。{@code thread} 始终是当前 Thread
 * projection，{@code cancelledUserMessages} 按 sequence 升序返回被取消的 user-like 消息内容（SET_* 与 SYSTEM
 * steering 不返回）。
 */
public record StopResult(
    boolean replayed,
    ThreadState thread,
    UUID stoppedTurnEndEntryId,
    int cancelledCommandCount,
    List<CancelledUserMessage> cancelledUserMessages) {

  public StopResult {
    thread = Objects.requireNonNull(thread, "thread");
    if (cancelledCommandCount < 0) {
      throw new IllegalArgumentException("cancelledCommandCount must not be negative");
    }
    cancelledUserMessages =
        List.copyOf(Objects.requireNonNull(cancelledUserMessages, "cancelledUserMessages"));
    long previous = 0L;
    for (CancelledUserMessage message : cancelledUserMessages) {
      if (message.sequence() <= previous) {
        throw new IllegalArgumentException(
            "cancelledUserMessages sequences must be strictly increasing");
      }
      previous = message.sequence();
    }
    if (cancelledUserMessages.size() > cancelledCommandCount) {
      throw new IllegalArgumentException(
          "cancelledUserMessages count must not exceed cancelledCommandCount");
    }
  }
}
