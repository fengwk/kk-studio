package fun.fengwk.kkstudio.core.systemsettings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * System settings 变更通知的应用处理器。
 *
 * <p>通知 payload 只用于唤醒，不承载可信状态；普通通知与 listener 重连同步都必须回读数据库权威记录。运行期回读失败只记录日志， 不得中断统一 listener。
 */
@Slf4j
@Component
public class SystemSettingsChangeHandler {

  private final SystemSettingsRepository repository;
  private final SystemSettingsSnapshot snapshot;

  public SystemSettingsChangeHandler(
      SystemSettingsRepository repository, SystemSettingsSnapshot snapshot) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  /** 处理一条变更通知；payload 无论为空或畸形都不影响权威回读。 */
  public void onNotification(String payload) {
    refresh("notification");
  }

  /** listener 建连或重连后执行一次完整权威同步。 */
  public void onResync() {
    refresh("resync");
  }

  private void refresh(String reason) {
    try {
      SystemSettingsRepository.SystemSettingsRecord record = repository.get();
      if (record == null) {
        log.warn("system settings row is missing after {}; snapshot unchanged", reason);
        return;
      }
      snapshot.replaceIfNotOlder(record);
    } catch (RuntimeException error) {
      log.warn("cannot refresh system settings snapshot after {}", reason, error);
    }
  }
}
