package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.util.List;
import java.util.Objects;

/**
 * 有界 realtime projection 的最佳努力写入端口。
 *
 * <p>实现通过 PostgreSQL notification 发布 live overlay。调用方必须隔离 sink 失败：projection 丢失只能降低实时体验，绝不能改变
 * durable Invocation 终态或 Thread 唤醒。
 */
@FunctionalInterface
public interface RealtimeEventSink {

  void append(RealtimeEvent event);

  /**
   * 按给定顺序批量发布同一已提交有界批次产生的事件。
   *
   * <p>默认实现逐条委托 {@link #append}。实现可以覆写为按有界分块的单次往返批量写入，但必须保持每个事件的 envelope、sequence 与整体顺序不变。批量写入与
   * {@link #append} 同为 best-effort：调用方以**调用**为隔离单位（失败后不重试、不改写 durable 状态或终态），实现内部允许在首个失败处中断本次调用
   * 剩余事件——已投递成功的前缀保持投递，剩余部分只降低实时体验，由客户端 Snapshot/resync 恢复。
   */
  default void appendAll(List<RealtimeEvent> events) {
    Objects.requireNonNull(events, "events");
    for (RealtimeEvent event : events) {
      append(event);
    }
  }
}
