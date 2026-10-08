package fun.fengwk.kkstudio.web.events;

import org.springframework.stereotype.Component;

/**
 * 执行树失效：{@code harness_thread} 写事务内由写入口把 payload 聚合为真实执行根 id，浏览器据此回读该根的树。
 *
 * <p>子 Thread 的变化不推进根 Thread 的 version，因此本资源按根 id 定点失效，绝不复用或伪造根 version。
 */
@Component
final class ExecutionTreeChangeHub extends NotificationInvalidationHub {}
