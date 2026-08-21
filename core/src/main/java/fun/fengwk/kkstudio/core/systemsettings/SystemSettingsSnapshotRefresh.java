package fun.fengwk.kkstudio.core.systemsettings;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 从权威 {@code system_setting} 行回读并替换进程内快照。
 *
 * <p>行缺失只记警告并保持当前快照：运行期刷新不能把已启动进程打成失败。
 */
@Slf4j
final class SystemSettingsSnapshotRefresh implements Runnable {

  private final SystemSettingsRepository repository;
  private final SystemSettingsSnapshot snapshot;

  SystemSettingsSnapshotRefresh(
      SystemSettingsRepository repository, SystemSettingsSnapshot snapshot) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  @Override
  public void run() {
    SystemSettingsRepository.SystemSettingsRecord record = repository.get();
    if (record == null) {
      log.warn("system settings row is missing; snapshot unchanged");
      return;
    }
    snapshot.replace(record.settings());
  }
}
