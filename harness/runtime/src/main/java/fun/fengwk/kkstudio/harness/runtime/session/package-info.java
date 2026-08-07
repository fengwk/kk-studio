/**
 * Canonical Session 域与不可变消息内容记录。
 *
 * <p>{@link Session} 对齐 PostgreSQL {@code harness_session}；Entry Tree 的不可变节点与 payload 位于 {@code
 * harness.runtime.history}。消息内容类型供 Entry payload 编解码与规划路径共享。
 */
package fun.fengwk.kkstudio.harness.runtime.session;
