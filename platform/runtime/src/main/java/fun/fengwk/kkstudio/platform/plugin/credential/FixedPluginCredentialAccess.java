package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;

/** 保存前校验配置时使用的候选凭据视图：返回本次将要写入的凭据（可为空），绝不读取或作废真实凭据行。 */
public final class FixedPluginCredentialAccess implements PluginCredentialAccess {

  private final PluginCredentialSnapshot snapshot;

  public FixedPluginCredentialAccess(PluginCredentialSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  @Override
  public PluginCredentialSnapshot resolve() {
    if (snapshot == null) {
      throw new PluginCredentialUnavailableException(
          PluginCredentialUnavailableReason.NOT_CONNECTED,
          "the candidate configuration has no credential for this plugin");
    }
    return snapshot;
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    return false;
  }
}
