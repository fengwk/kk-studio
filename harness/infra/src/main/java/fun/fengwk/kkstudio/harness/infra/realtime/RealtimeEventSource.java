package fun.fengwk.kkstudio.harness.infra.realtime;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Transport-facing realtime event source 端口：{@link #subscribe} 是唯一抽象方法，{@link #close()} 释放整个源。
 *
 * <p>实现是 live notification overlay：{@link #subscribe} 之后发布的事件才会到达 {@code onEvent}，不重放历史。 订阅句柄
 * {@link AutoCloseable#close()} 后不再回调。监听器失联或消息无法解码时对本地订阅触发 {@code onResync}， 调用方必须让客户端整体快照恢复。
 */
public interface RealtimeEventSource extends AutoCloseable {

  /**
   * 订阅一个 Thread 的 realtime 事件流。
   *
   * @param threadId 目标 Thread
   * @param onEvent 接收解码后的 {@link RealtimeEvent}；可能在任何线程回调，不得阻塞
   * @param onResync 监听器失联/消息损坏时触发；可能在任何线程回调，不得阻塞
   * @return 订阅句柄，{@code close()} 释放；重复 close 幂等
   */
  AutoCloseable subscribe(UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync);
}
