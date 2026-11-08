package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

/**
 * IDE 视图授权的窄出口：owner 侧 claim/close 元数据事件，供浏览器会话与数据面按完整 Daemon 进程身份 + viewId + epoch 维护授权。
 *
 * <p>授权作用域与 Daemon 进程身份一致：同一实例断线重连后视图仍在，授权与 token 保留；另一个实例接管该 Environment 后，授权入口先清掉旧实例的登记与 token
 * 再登记新实例，因此旧代际既不占新代际容量，也不能被复用。调用方必须在调用前用本节点当前 READY 连接复核传入的 {@code identity}：迟到旧实例结果 不得重新登记授权，也不能借旧
 * close 撤销新实例的会话。实现必须是无副作用的本地内存登记，且只在 owner 节点被调用。
 */
public interface IdeViewAuthorizationListener {

  /**
   * 确认当前 READY Daemon 实例的授权代际：清除该 Environment 旧实例的登记与 token，并按该实例预检视图容量。
   *
   * @throws IllegalStateException 该实例已达视图上界
   */
  void ensureViewCapacity(DaemonConnectionIdentity identity);

  /** 一个 IDE 视图已创建或恢复：授权其 view+epoch 的受管 workspace。 */
  void viewOpened(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath);

  /** 一个 IDE 视图的某个 epoch 已关闭：撤销该进程该 epoch 的会话，但不得影响其它代际的新绑定。 */
  void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch);
}
