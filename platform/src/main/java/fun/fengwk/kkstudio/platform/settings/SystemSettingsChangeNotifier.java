package fun.fengwk.kkstudio.platform.settings;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.Objects;

/**
 * 在 system settings 的写事务中通过统一 NotificationBus 发布失效信号。
 *
 * <p>总线在装配期读取 {@link SystemSettingsSnapshot} 的轮询/重连节奏，而快照又依赖本类所在的仓库，因此这里延迟解析总线：装配期只注入代理，
 * 首次发布时才取到已建好的单例，不引入第二套发布通道。
 */
@Component
public class SystemSettingsChangeNotifier {

  private final NotificationBus bus;

  public SystemSettingsChangeNotifier(@Lazy NotificationBus bus) {
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  /** 以写后 version 发布一次失效提示；调用方必须已处于真实事务中。 */
  public void versionChanged(long version) {
    requireTransaction();
    bus.publish(PlatformNotifications.SETTINGS_CHANGED, NotificationAddress.broadcast(), version);
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "System settings change notification requires an active transaction");
    }
  }
}
