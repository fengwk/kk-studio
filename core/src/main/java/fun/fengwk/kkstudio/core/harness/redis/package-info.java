/**
 * 生产 Redis adapter：实现 {@link fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink}。
 *
 * <p>本包不保存 durable state，也不实现 subscriber、worker lifecycle、Web SSE 或 PostgreSQL recovery。Redis 仅作为
 * lossy wake hint 与有界 realtime projection 通道；调用方负责隔离失败，adapter 只做严格 deterministic wire 编码与原生 Redis
 * 操作。
 */
package fun.fengwk.kkstudio.core.harness.redis;
