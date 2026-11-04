/**
 * 有损实时投影的公开契约：live overlay 事件、其事件种类、跨节点 payload 上限与不可用错误。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.realtime.RealtimeEvent} 是只用于短期 UI 重放的非 durable 投影， 每个
 * subtype 携带归属 Thread、durable subject 与创建时间，{@link
 * fun.fengwk.kkstudio.harness.api.realtime.RealtimeEventType} 是其稳定的 wire 种类名。{@link
 * fun.fengwk.kkstudio.harness.api.realtime.RealtimePayloadLimits} 固定 oversize payload 跨节点传输的唯一有界常量，
 * {@link fun.fengwk.kkstudio.harness.api.realtime.RealtimePayloadUnavailableException} 表示该 payload
 * 明确不可用。消费方只据此触发 RESYNC，权威事实始终来自 durable 快照。
 *
 * <p>wire 形态在 {@link fun.fengwk.kkstudio.harness.codec.RealtimeEventJsonCodec}；通知信封、缓冲、跨节点回拉与
 * 恢复都在平台实现，本包不含传输与持久化。
 */
package fun.fengwk.kkstudio.harness.api.realtime;
