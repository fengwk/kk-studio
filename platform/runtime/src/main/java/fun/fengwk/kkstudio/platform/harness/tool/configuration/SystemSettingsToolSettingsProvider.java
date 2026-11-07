package fun.fengwk.kkstudio.platform.harness.tool.configuration;

import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettings;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.util.Objects;

/**
 * 基于 {@link SystemSettingsSnapshot} 的 {@link ToolSettingsProvider}。
 *
 * <p>每次 {@link #get()} 都读取当前进程内快照并转换为 {@link ToolSettings}：启动时只读一次数据库，变更由通知回读后的快照替换驱动； permission
 * 对下一次 preflight 立即生效，无需重启或重复读库。ToolSettings 构造器会做不可变副本，转换本身不会引入额外权限源。
 */
public final class SystemSettingsToolSettingsProvider implements ToolSettingsProvider {

  private final SystemSettingsSnapshot systemSettingsSnapshot;

  public SystemSettingsToolSettingsProvider(SystemSettingsSnapshot systemSettingsSnapshot) {
    this.systemSettingsSnapshot =
        Objects.requireNonNull(systemSettingsSnapshot, "systemSettingsSnapshot");
  }

  @Override
  public ToolSettings get() {
    SystemSettings.Tool tool = systemSettingsSnapshot.get().tool();
    return new ToolSettings(tool.permission());
  }
}
