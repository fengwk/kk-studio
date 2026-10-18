package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;

/** 宿主唯一的凭据读取端口；不经 {@link fun.fengwk.kkstudio.platform.plugin.PluginContext} 交给插件。 */
public interface PluginCredentialStore {

  /** 管理面投影：只有状态与路由元数据，不含秘密。 */
  PluginCredentialProjection projection(String pluginId);

  /**
   * 解析本次调用可用的凭据。
   *
   * @throws PluginCredentialUnavailableException 没有可用凭据（未连接、刷新中、已拒绝、结果未知或已过期）
   */
  PluginCredentialSnapshot resolve(String pluginId);

  /** 远端确定性拒绝本次已用凭据时作废它；以快照版本做 CAS，返回是否真的作废了该行。 */
  boolean rejectUsed(PluginCredentialSnapshot snapshot, String error);
}
