/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC 或 HTTP 类型。PostgreSQL HarnessStore / Work 是持久化事实；PostgreSQL notification
 * adapter 只提供 lossy live overlay。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.port.TurnResolver} 是同步、无副作用、事务外的解析端口；{@link
 * fun.fengwk.kkstudio.harness.runtime.port.ModelGateway} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.ToolGateway} 是 execution admission / stream 端口。Model
 * admission 固定为 Started / Busy / Rejected / Indeterminate，Tool admission 固定为 Started / RetryLater /
 * Rejected / Indeterminate；重复或陈旧的 Listener 回调由 Runtime 实施所有权围栏与陈旧回调围栏拦截，两阶段激活规范要求在持久化 RUNNING
 * 落地前不得打开回调门控。{@link fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink} 保持现有唯一 realtime
 * 接口，不另造 event bus。
 */
package fun.fengwk.kkstudio.harness.runtime.port;
