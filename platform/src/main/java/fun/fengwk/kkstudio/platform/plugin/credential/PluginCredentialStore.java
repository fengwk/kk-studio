package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;

/**
 * Plugin 凭据的唯一持久化入口。
 *
 * <p>实现负责 AES-256-GCM 加解密、CAS version、token-fenced lease 与状态投影；调用方（管理面与 Tool）只看到安全投影或本次调用所需的解密快照，
 * 拿不到 repository、密文、主密钥或 lease。未认证时没有行，断连即删除行。
 *
 * <p>读写都 fail closed：主密钥不可用或密文认证失败时不再读写凭据，而是以 {@code KEY_UNAVAILABLE} 投影与 {@link
 * PluginKeyUnavailableException} 表达。
 */
public interface PluginCredentialStore {

  /** 管理面安全投影；从未认证过的 Plugin 返回 {@code NOT_CONNECTED}。 */
  PluginCredentialProjection projection(String pluginId);

  /**
   * 解析本次调用所需的解密快照。
   *
   * @throws PluginCredentialUnavailableException 无凭据、主密钥不可用、刷新在途、需重新登录、结果未知或凭据已过期
   */
  PluginCredentialSnapshot resolve(String pluginId);

  /** 以新凭据材料覆盖该 Plugin 的凭据行（不存在则创建），并重置为 {@code CONNECTED}。 */
  PluginCredentialProjection save(String pluginId, PluginCredentialMaterial material);

  /** 断连：删除该 Plugin 的凭据行；迟到 finalize 由 lease token 与 version 围栏拒绝。 */
  void delete(String pluginId);
}
