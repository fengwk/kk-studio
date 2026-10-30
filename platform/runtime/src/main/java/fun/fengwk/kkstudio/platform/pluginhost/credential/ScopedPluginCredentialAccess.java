package fun.fengwk.kkstudio.platform.pluginhost.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;

import java.util.Objects;

/** 把宿主唯一的 {@link PluginCredentialStore} 收窄成单个 Plugin 的访问视图：没有 pluginId 参数，拒绝别的 Plugin 的快照。 */
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
