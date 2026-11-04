/**
 * Canonical Session 与消息值类型：{@link Session} 聚合与 sealed 消息内容家族。
 *
 * <p>{@link AgentMessage} 把 role 与不可变 {@link AgentMessageContent} 列表绑成已完成消息。
 *
 * <p>内容家族区分 durable 类型与瞬时类型，durable 可持久化并投影，瞬时只存在于请求形态。
 *
 * <p>{@link AssistantMessageMetadata} 只携带 Provider 报告的 stop reason、usage 与流式计时。
 *
 * <p>{@link SystemReminder} 是公开消息形态上的纯值函数：用精确的 {@code <system-reminder>} 定界符构造和判别运行时注入的 USER
 * 上下文提醒，不读时钟、不写状态。
 *
 * <p>消息 JSON 编解码与命令 payload 编解码在 {@code harness.codec}。
 */
package fun.fengwk.kkstudio.harness.api.session;
