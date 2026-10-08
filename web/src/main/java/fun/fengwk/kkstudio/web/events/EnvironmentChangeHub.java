package fun.fengwk.kkstudio.web.events;

import org.springframework.stereotype.Component;

/** Environment 连接行的状态、被接受 metadata 与租约代币变化，投递同一全局失效信号；到期由读取投影判断。 */
@Component
final class EnvironmentChangeHub extends NotificationInvalidationHub {}
