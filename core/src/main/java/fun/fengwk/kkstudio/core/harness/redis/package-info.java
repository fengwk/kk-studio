/**
 * 生产 Redis adapter：实现 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier}、activation subscriber 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink}。
 *
 * <p>本包不保存 durable state、worker lifecycle 或 Web SSE。Redis 仅作为 lossy wake hint 与有界 realtime
 * projection 通道；subscriber 严格解码 target 并把它交给进程内 dispatcher，调用方负责隔离失败。
 */
package fun.fengwk.kkstudio.core.harness.redis;
