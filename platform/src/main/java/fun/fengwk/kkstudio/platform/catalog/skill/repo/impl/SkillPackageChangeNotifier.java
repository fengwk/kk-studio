package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.Objects;

/** 在 Skill Package 的写事务中通过统一 NotificationBus 发送失效信号。 */
@Component
public class SkillPackageChangeNotifier {

  private final NotificationBus bus;

  public SkillPackageChangeNotifier(NotificationBus bus) {
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  /** 以 package 名发布一次失效提示；调用方必须已处于真实事务中。 */
  public void packageChanged(String packageName) {
    Objects.requireNonNull(packageName, "packageName");
    requireTransaction();
    bus.publish(
        PlatformNotifications.SKILL_PACKAGE_CHANGED, NotificationAddress.broadcast(), packageName);
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Skill package change notification requires an active transaction");
    }
  }
}
