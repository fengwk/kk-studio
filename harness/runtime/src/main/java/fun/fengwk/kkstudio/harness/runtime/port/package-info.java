/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC 或 HTTP 类型。PostgreSQL HarnessStore / Work 是持久化事实；PostgreSQL notification
 * adapter 只提供 lossy live overlay。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.port.TurnResolver} 是同步、无副作用、调用方事务内的解析端口；{@link
 * fun.fengwk.kkstudio.harness.runtime.port.ModelGateway} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.ToolGateway} 是 execution 端口：两者都先做纯本地 admission（Admitted
 * / Busy / Rejected，均发生在任何请求发出之前），再在调用线程上阻塞执行至终态，取消即中断运行线程；Runtime 在请求发出前持久化 RUNNING，并以所有权围栏拦截
 * 陈旧的终态提交。{@link fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink} 保持现有唯一 realtime 接口，不另造
 * event bus。
 */
package fun.fengwk.kkstudio.harness.runtime.port;
