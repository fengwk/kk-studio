package fun.fengwk.kkstudio.platform.environment.server;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.EnvironmentChangeNotifications;
import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationSource;

/**
 * 把 {@code environment_changed} 提示转成 daemon-server 的按 Environment 配置对账。
 *
 * <p>订阅回调只调度，不做数据库查询；查询由 server 的受管 executor 在会话锁外执行。LISTEN 重连触发的 Resync 重读本机全部会话。
 */
@Component
public class EnvironmentConfigChangeSubscriber {

  private final Runnable unsubscribe;

  public EnvironmentConfigChangeSubscriber(
      EnvironmentChangeNotifications notifications, EnvironmentDaemonServer server) {
    this.unsubscribe =
        notifications.subscribe(
            null,
            event -> {
              if (event instanceof NotificationInvalidationSource.Event.Changed changed) {
                server.environmentChanged(EnvironmentId.of(changed.key()));
              } else {
                server.resyncEnvironmentConfigs();
              }
            });
  }

  @PreDestroy
  public void close() {
    unsubscribe.run();
  }
}
