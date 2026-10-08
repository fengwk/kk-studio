package fun.fengwk.kkstudio.web.events;

import org.springframework.stereotype.Component;

/**
 * 待处理交互失效：{@code harness_tool_invocation} 进出 {@code WAITING_APPROVAL} / {@code WAITING_INPUT}
 * 或删除后由写入口投递真实执行根 id，浏览器同时回读全局红点与对应根的交互列表。
 */
@Component
final class InteractionChangeHub extends NotificationInvalidationHub {}
