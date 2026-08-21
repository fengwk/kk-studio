/**
 * 有界 Redis realtime overlay 适配：{@link fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink} 的
 * Redis Pub/Sub 实现与 transport-facing {@link
 * fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventSource}。
 *
 * <p>Redis 只是按 Thread 分 channel 的 lossy live projection，只承载短期可丢的 Model delta / Tool partial；REST
 * snapshot 是恢复真值，version 事件只负责提示重新读取。sink 失败只影响实时体验，绝不影响 Invocation 终态或 Thread 唤醒。
 */
package fun.fengwk.kkstudio.harness.runtime.spring.redis;
