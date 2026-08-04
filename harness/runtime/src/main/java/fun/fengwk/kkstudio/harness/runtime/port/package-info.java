/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC、Redis 或 HTTP 类型。PostgreSQL HarnessStore / Work 是 durable truth；Redis adapter
 * 只提供 lossy realtime projection。
 *
 * <p>{@link TurnResolver} 是同步、无副作用、事务外的解析端口；{@link ModelGateway} 与 {@link ToolGateway} 是 execution
 * admission / stream 端口，各自以 sealed 结果类型固定 admission certainty（Started / Busy / Rejected /
 * Indeterminate，Tool 另有 Overloaded），回调 duplicate / stale 由 Runtime fence。{@link RealtimeEventSink}
 * 保持现有 唯一 realtime 接口，不另造 event bus。
 */
package fun.fengwk.kkstudio.harness.runtime.port;
