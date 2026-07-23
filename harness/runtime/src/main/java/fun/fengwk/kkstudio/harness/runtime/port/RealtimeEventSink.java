package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

/**
 * 有界 realtime projection 的最佳努力写入端口。
 *
 * <p>实现通常写入 Redis Stream。调用方必须隔离 sink 失败：projection 丢失只能降低实时体验，绝不能改变 durable Invocation 终态或 Thread
 * 唤醒。
 */
@FunctionalInterface
public interface RealtimeEventSink {

  void append(RealtimeEvent event);
}
