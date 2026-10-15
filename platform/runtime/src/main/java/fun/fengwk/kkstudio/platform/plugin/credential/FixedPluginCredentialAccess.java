package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;

import java.util.Objects;

/**
 * 只服务一份固定快照的 {@link PluginCredentialAccess}：配置提交的私有候选在真正落库前用它把「本次 patch 的有效秘密视图」交给 Plugin 的 {@code
 * configure}，绝不提前改活动凭据，也绝不触碰权威 store。
 *
 * <p>{@link #rejectUsed} 是空操作：候选实例不承担调用期作废，返回 false。
 */
public final class FixedPluginCredentialAccess implements PluginCredentialAccess {

  private final PluginCredentialReference reference;
  private final PluginCredentialSnapshot snapshot;

  /**
   * @param reference 候选引用的冻结身份
   * @param snapshot 候选秘密快照；为 null 表示该候选当前没有凭据
   */
  public FixedPluginCredentialAccess(
      PluginCredentialReference reference, PluginCredentialSnapshot snapshot) {
    this.reference = Objects.requireNonNull(reference, "reference");
    if (snapshot != null && !snapshot.reference().equals(reference)) {
      throw new IllegalArgumentException(
          "candidate snapshot does not match the candidate reference");
    }
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
