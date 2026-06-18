/**
 * SessionEvent 到运行上下文消息的投影器。
 *
 * <p>投影器负责从 branch 事件链恢复当前配置与 ChatMessage 上下文。结构坏数据由 session manager 处理，
 * 内容坏数据在本层尽量兼容恢复，并通过 warn 日志保持可观测。</p>
 */
package fun.fengwk.kkstudio.agent.session.projection;
