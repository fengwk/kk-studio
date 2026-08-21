package fun.fengwk.kkstudio.core.systemsettings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内 system settings 的 live 快照。
 *
 * <p>启动时从权威 {@link SystemSettingsProvider} 物化一次；此后 {@link SystemSettingsService} 的 PUT 在事务 {@code
 * afterCommit} 成功后通过 {@link #replace} 原子替换为回读的权威 {@link SystemSettings}，回滚绝不更新内存。 {@link #get()}
 * 每次返回当前值，aiRuntime 相关决策点（compaction / retry / subagent）现读现用。
 *
 * <p>其余 restart-required 配置 bean 仍注入本单例并在装配期读取 {@link #get()}；本类仍是唯一装配期 DB 读取点，不允许各配置各自再注入 {@code
 * SystemSettingsProvider} 现读现解（唯一例外是运行期按调用读取的 {@code SystemSettingsToolSettingsProvider}）。
 */
@Component
public class SystemSettingsSnapshot {

  private final AtomicReference<SystemSettings> settings;

  /** Spring 装配：启动时立即读取当前数据库权威配置；缺失或损坏属于部署不变量错误，直接失败。 */
  @Autowired
  public SystemSettingsSnapshot(SystemSettingsProvider provider) {
    Objects.requireNonNull(provider, "provider");
    this.settings =
        new AtomicReference<>(Objects.requireNonNull(provider.get(), "system settings snapshot"));
  }

  /** 测试 / 手动装配：直接提供已经物化好的配置。 */
  public SystemSettingsSnapshot(SystemSettings settings) {
    this.settings = new AtomicReference<>(Objects.requireNonNull(settings, "settings"));
  }

  /** 返回当前 system settings 快照。 */
  public SystemSettings get() {
    return settings.get();
  }

  /** 原子替换当前快照；仅由 {@link SystemSettingsService} 在事务 afterCommit 成功后调用。 */
  public void replace(SystemSettings settings) {
    this.settings.set(Objects.requireNonNull(settings, "settings"));
  }
}
