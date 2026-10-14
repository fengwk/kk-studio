package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;

/**
 * 单个已安装 Plugin 的凭据访问视图：绑定安装 {@code pluginId}，没有 pluginId 参数。
 *
 * <p>它是 {@link PluginCredentialStore} 的 scoped 投影：Plugin 只能读取自己的凭据快照与作废自己本次已用的快照，拿不到
 * store、repository 或主密钥，也无法读写其它 Plugin 的凭据。实现必须拒绝不属于本 Plugin 的快照。
 */
public interface PluginCredentialAccess {

  /**
   * 解析本次调用所需的解密快照。
   *
   * @throws PluginCredentialUnavailableException 无凭据、主密钥不可用、刷新在途、需重新登录、结果未知或凭据已过期
   */
  PluginCredentialSnapshot resolve();

  /**
   * 调用期确定性认证拒绝：只把本 Plugin 本次已用的凭据置为 {@code REAUTH_REQUIRED}。
   *
   * @return 是否实际作废了当前行
   * @throws IllegalArgumentException 快照不属于本 Plugin 时
   */
  boolean rejectUsed(PluginCredentialSnapshot snapshot, String error);
}
