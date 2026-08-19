package fun.fengwk.kkstudio.core.systemsettings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 启动时从权威 {@link SystemSettingsProvider} 物化一次的系统设置快照。
 *
 * <p>长生命周期的客户端 / executor / bean 拓扑（S3、ComfyUI、OpenCLI、Canvas executor/realtime 等）与运行期行为统一读取本
 * 快照；SystemSettings 的运行时更新在应用重启后生效（restart-required）。数据库单行仍是唯一权威源，{@code SystemSettingsProvider}
 * 每次调用现读现解。
 *
 * <p>本类是唯一装配期 DB 读取点：所有 restart-required 配置 bean 都注入本单例并读取 {@link #get()}，整个 bean 图只共享这一次
 * 启动读取，不允许各配置各自再注入 {@code SystemSettingsProvider} 现读现解（唯一例外是运行期按调用读取的 {@code
 * SystemSettingsToolSettingsProvider}）。
 */
@Component
public class SystemSettingsSnapshot {

  private final SystemSettings settings;

  /** Spring 装配：启动时立即读取当前数据库权威配置；缺失或损坏属于部署不变量错误，直接失败。 */
  @Autowired
  public SystemSettingsSnapshot(SystemSettingsProvider provider) {
    Objects.requireNonNull(provider, "provider");
    this.settings = Objects.requireNonNull(provider.get(), "system settings snapshot");
  }

  /** 测试 / 手动装配：直接提供已经物化好的配置。 */
  public SystemSettingsSnapshot(SystemSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** 返回启动时捕获的系统设置。 */
  public SystemSettings get() {
    return settings;
  }
}
