package fun.fengwk.kkstudio.web.events;

import org.springframework.stereotype.Component;

/**
 * Environment 连接状态失效：{@code environment_connection} 的状态/被接受 metadata/租约代币变化，以及租约到期扫描，都投递同一 全局资源信号。
 */
@Component
final class EnvironmentChangeHub extends NotificationInvalidationHub {

  static final String CHANNEL = "environment_changed";
}
