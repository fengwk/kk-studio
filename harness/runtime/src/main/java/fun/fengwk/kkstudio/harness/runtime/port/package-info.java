/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC 或 HTTP 类型。PostgreSQL HarnessStore / Work 是 durable truth；PostgreSQL
 * notification adapter 只提供 lossy live overlay。
 *
 * <p>{@link TurnResolver} 是同步、无副作用、事务外的解析端口；{@link ModelGateway} 与 {@link ToolGateway} 是 execution
 * admission / stream 端口。Model admission 固定为 Started / Busy / Rejected / Indeterminate，Tool
 * admission 固定为 Started / RetryLater / Rejected / Indeterminate；回调 duplicate / stale 由 Runtime
 * fence。{@link RealtimeEventSink} 保持现有唯一 realtime 接口，不另造 event bus。
 */
package fun.fengwk.kkstudio.harness.runtime.port;
