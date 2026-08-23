package fun.fengwk.kkstudio.platform.ai.runtime.tool.configuration;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsProvider;

import java.util.Objects;

/**
 * 基于数据库 {@link SystemSettings} 的 {@link ToolSettingsProvider}。
 *
 * <p>每次 {@link #get()} 都读取当前数据库权威配置并转换为 {@link ToolSettings}：permission 对下一次 preflight 生效，
 * defaultYolo 在下一次未显式指定模式的 Chat 创建时被捕获；两者都无需重启。ToolSettings 构造器会做不可变副本，转换本身不会引入额外权限源。
 */
public final class SystemSettingsToolSettingsProvider implements ToolSettingsProvider {

  private final SystemSettingsProvider systemSettingsProvider;

  public SystemSettingsToolSettingsProvider(SystemSettingsProvider systemSettingsProvider) {
    this.systemSettingsProvider =
        Objects.requireNonNull(systemSettingsProvider, "systemSettingsProvider");
  }

  @Override
  public ToolSettings get() {
    SystemSettings.Tool tool = systemSettingsProvider.get().tool();
    return new ToolSettings(tool.permission(), tool.defaultYolo());
  }
}
