package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

/**
 * IDE 视图授权的窄出口：owner 侧 claim/close 元数据事件，供浏览器会话与数据面按 Daemon 代际 + viewId + epoch 维护授权。
 *
 * <p>授权生命周期严格跟随 Daemon 的真实视图生命周期：claim 成功即授权、关闭/detach/恢复换代即撤销该 epoch 的会话；实例接管时清空整个代际。
 * 同一实例断线重连时视图仍在，授权保留。实现必须是无副作用的本地内存登记，且只在 owner 节点被调用。
 */
public interface IdeViewAuthorizationListener {

  /**
   * 在任何 Native 副作用之前预检容量：该 Environment 已无可授权的视图额度时显式失败。
   *
   * @throws IllegalStateException 已达视图/代际上界
   */
  void ensureViewCapacity(EnvironmentId environmentId);

  /** 一个 IDE 视图已创建或恢复：授权其 view+epoch 的受管 workspace。 */
  void viewOpened(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath);

  /** 一个 IDE 视图的某个 epoch 已关闭/分离：撤销该 epoch 的会话，但不得影响已换代的新绑定。 */
  void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch);
}
