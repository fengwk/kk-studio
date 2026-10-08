package fun.fengwk.kkstudio.web.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.notification.DefaultNotificationBus;

import java.util.Objects;

/**
 * Actuator 通知总线健康指标。
 *
 * <p>传输连接或任一订阅信箱进入终态失败即为 DOWN；健康检查只读内存状态，不发起数据库往返，也不向 Health response 泄露任何 payload。
 */
@Component
public class NotificationBusHealthIndicator implements HealthIndicator {

  private final DefaultNotificationBus notificationBus;

  public NotificationBusHealthIndicator(DefaultNotificationBus notificationBus) {
    this.notificationBus = Objects.requireNonNull(notificationBus, "notificationBus");
  }

  @Override
  public Health health() {
    return notificationBus.healthy() ? Health.up().build() : Health.down().build();
  }
}
