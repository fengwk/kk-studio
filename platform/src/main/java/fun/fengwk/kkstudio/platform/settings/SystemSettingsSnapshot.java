package fun.fengwk.kkstudio.platform.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内 system settings 的 live 快照。
 *
 * <p>启动时从权威 {@link SystemSettingsRepository} 物化一次；此后 {@link SystemSettingsService} 的 PUT 在事务 {@code
 * afterCommit} 成功后回读权威记录并原子替换。其他节点在收到变更通知或 listener 重连后通过 {@link SystemSettingsChangeHandler}
 * 权威回读。带版本替换使用 CAS 门控，较旧的并发回读不能覆盖较新的快照。回滚绝不更新内存。 {@link #get()} 每次返回当前值。
 *
 * <p>live 决策点（aiRuntime compaction/retry/subagent、tool.gateway、environment.runtime、
 * storageMedia.canvasMedia，以及 permission/defaultYolo）每次现读。其余 restart-required 配置 bean 仍在装配期读取
 * {@link #get()}。本类是唯一装配期 DB 读取点，不允许各配置各自再注入 {@code SystemSettingsProvider} 现读现解。
 */
@Component
public class SystemSettingsSnapshot {

  private static final long UNVERSIONED = -1L;

  private final AtomicReference<VersionedSettings> current;

  /** Spring 装配：启动时立即读取当前数据库权威配置；缺失或损坏属于部署不变量错误，直接失败。 */
  @Autowired
  public SystemSettingsSnapshot(SystemSettingsRepository repository) {
    Objects.requireNonNull(repository, "repository");
    this.current = new AtomicReference<>(requireVersionedSettings(repository.get()));
  }

  /** 测试 / 手动装配：直接提供已经物化好的配置。 */
  public SystemSettingsSnapshot(SystemSettings settings) {
    this.current =
        new AtomicReference<>(
            new VersionedSettings(
                Objects.requireNonNull(settings, "system settings snapshot"), UNVERSIONED));
  }

  /** 返回当前 system settings 快照。 */
  public SystemSettings get() {
    return current.get().settings();
  }

  /** 不改变版本地原子替换当前配置；用于测试或手动装配。权威回读必须使用带版本门控的替换。 */
  public void replace(SystemSettings settings) {
    Objects.requireNonNull(settings, "settings");
    current.updateAndGet(existing -> new VersionedSettings(settings, existing.version()));
  }

  /** 仅当权威记录不旧于当前快照时原子替换；返回是否采用该记录。 */
  boolean replaceIfNotOlder(SystemSettingsRepository.SystemSettingsRecord record) {
    VersionedSettings replacement = requireVersionedSettings(record);
    while (true) {
      VersionedSettings existing = current.get();
      if (replacement.version() < existing.version()) {
        return false;
      }
      if (current.compareAndSet(existing, replacement)) {
        return true;
      }
    }
  }

  private static VersionedSettings requireVersionedSettings(
      SystemSettingsRepository.SystemSettingsRecord record) {
    if (record == null) {
      throw new IllegalStateException("system settings row is missing");
    }
    return new VersionedSettings(
        Objects.requireNonNull(record.settings(), "system settings snapshot"), record.version());
  }

  private record VersionedSettings(SystemSettings settings, long version) {}
}
