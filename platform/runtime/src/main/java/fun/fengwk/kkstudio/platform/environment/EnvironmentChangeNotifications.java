package fun.fengwk.kkstudio.platform.environment;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationHub;
import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationSource;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 环境失效通知的生产方入口：独占 {@code environment_changed} channel，只解析 environmentId payload 并做进程内分发。
 *
 * <p>写侧 {@link EnvironmentChangeNotifier} 在事实事务内按同一 channel 投递 environmentId；本类不创建 LISTEN
 * 连接，也不复制环境事实。 组合根只把通知与 resync 转发给本入口，再把失效信号转发给浏览器。
 */
@Component
public final class EnvironmentChangeNotifications implements NotificationInvalidationSource {

  /** {@code environment_changed} 的 PostgreSQL channel。 */
  public static final String CHANNEL = "environment_changed";

  private final NotificationInvalidationHub hub = new NotificationInvalidationHub();

  @Override
  public Runnable subscribe(UUID key, Consumer<Event> consumer) {
    return hub.subscribe(key, consumer);
  }

  /** 由组合根的共享 LISTEN 循环交付一条环境失效通知。 */
  public void onNotification(String payload) {
    hub.onNotification(payload);
  }

  /** 建连/重连：要求全部订阅整体回读。 */
  public void broadcastResync() {
    hub.broadcastResync();
  }
}
