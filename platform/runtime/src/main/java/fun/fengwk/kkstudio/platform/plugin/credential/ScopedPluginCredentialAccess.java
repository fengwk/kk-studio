package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;

import java.util.Objects;

/**
 * 绑定单个安装 {@code pluginId} 与其冻结 {@link PluginCredentialReference} 的 {@link
 * PluginCredentialAccess}：把宿主唯一 {@link PluginCredentialStore} 收窄成该 Plugin 在本次配置 epoch 的访问视图。
 *
 * <p>它没有 pluginId 参数，也没有按任意 id 读取、保存或删除凭据的能力；{@link #resolve()} 只解析构造时绑定的冻结引用，权威 store 每次都会重新核对
 * catalog 与该行的代际/上下文，因此旧上下文或已撤销代际无法解析到新秘密。{@link #rejectUsed} 先拒绝不属于本 Plugin 或不属于本引用的快照，再委托权威 store
 * 做 CAS 作废——这是本视图唯一的写入，且只作用于本 Plugin 本次已用的凭据行。插件拿不到 store、repository 或主密钥。
 */
public final class ScopedPluginCredentialAccess implements PluginCredentialAccess {

  private final String pluginId;
  private final PluginCredentialReference reference;
  private final PluginCredentialStore store;

  public ScopedPluginCredentialAccess(
      String pluginId, PluginCredentialReference reference, PluginCredentialStore store) {
    this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    this.reference = Objects.requireNonNull(reference, "reference");
    if (!pluginId.equals(reference.pluginId())) {
      throw new IllegalArgumentException("credential reference does not belong to this plugin");
    }
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public PluginCredentialSnapshot resolve() {
    return store.resolve(reference);
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (!pluginId.equals(snapshot.pluginId()) || !reference.equals(snapshot.reference())) {
      throw new IllegalArgumentException("credential snapshot does not belong to this plugin");
    }
    return store.rejectUsed(snapshot, error);
  }
}
