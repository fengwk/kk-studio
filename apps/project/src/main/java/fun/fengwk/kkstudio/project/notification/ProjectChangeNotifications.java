package fun.fengwk.kkstudio.project.notification;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationHub;
import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationSource;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Project 快照失效通知的生产方入口：独占 {@code project_issue_changed} channel，只解析 projectId payload 并做进程内分发。
 *
 * <p>写侧 {@code ProjectChangeNotifier} 在事实事务内按同一 channel 投递 projectId；本类不创建 LISTEN 连接，也不复制 Project
 * 事实。 组合根只把通知与 resync 转发给本入口，再把失效信号转发给浏览器。
 */
@Component
public final class ProjectChangeNotifications implements NotificationInvalidationSource {

  /** {@code project_issue_changed} 的 PostgreSQL channel。 */
  public static final String CHANNEL = "project_issue_changed";

  private final NotificationInvalidationHub hub = new NotificationInvalidationHub();

  @Override
  public Runnable subscribe(UUID key, Consumer<Event> consumer) {
    return hub.subscribe(key, consumer);
  }

  /** 由组合根的共享 LISTEN 循环交付一条 Project 失效通知。 */
  public void onNotification(String payload) {
    hub.onNotification(payload);
  }

  /** 建连/重连：要求全部订阅整体回读。 */
  public void broadcastResync() {
    hub.broadcastResync();
  }
}
