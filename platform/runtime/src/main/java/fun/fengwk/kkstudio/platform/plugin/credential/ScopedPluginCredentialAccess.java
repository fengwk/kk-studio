package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;

import java.util.Objects;

/**
 * 绑定单个安装 {@code pluginId} 的 {@link PluginCredentialAccess}：把宿主唯一 {@link PluginCredentialStore} 收窄成
 * 本 Plugin 的访问视图。
 *
 * <p>它没有 pluginId 参数，也没有按任意 id 读取、保存或删除凭据的能力；{@link #resolve()} 只解析构造时绑定的 pluginId； {@link
 * #rejectUsed} 先拒绝不属于本 Plugin 的快照，再委托权威 store 做 CAS 作废——这是本视图唯一的写入，且只作用于本 Plugin 本次已用的凭据行。插件拿不到
 * store、repository 或主密钥。
 */
public final class ScopedPluginCredentialAccess implements PluginCredentialAccess {

  private final String pluginId;
  private final PluginCredentialStore store;

  public ScopedPluginCredentialAccess(String pluginId, PluginCredentialStore store) {
    this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public PluginCredentialSnapshot resolve() {
    return store.resolve(pluginId);
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (!pluginId.equals(snapshot.pluginId())) {
      throw new IllegalArgumentException("credential snapshot does not belong to this plugin");
    }
    return store.rejectUsed(snapshot, error);
  }
}
