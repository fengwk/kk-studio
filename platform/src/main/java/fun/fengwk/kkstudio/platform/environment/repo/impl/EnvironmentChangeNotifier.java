package fun.fengwk.kkstudio.platform.environment.repo.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.Objects;
import java.util.UUID;

/** 在 Environment 持久化事务内通过统一 NotificationBus 发送失效信号。 */
@Component
public class EnvironmentChangeNotifier {

  private final NotificationBus bus;

  public EnvironmentChangeNotifier(NotificationBus bus) {
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  /** 发布指定 environment 的失效提示；调用方必须处于写事务中。 */
  public void environmentChanged(UUID environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    requireTransaction();
    bus.publish(
        PlatformNotifications.ENVIRONMENT_CHANGED, NotificationAddress.broadcast(), environmentId);
  }

  /** 校验当前线程处于真实写事务中；调用方必须在执行写之前检查。 */
  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Environment change notification requires an active transaction");
    }
  }
}
