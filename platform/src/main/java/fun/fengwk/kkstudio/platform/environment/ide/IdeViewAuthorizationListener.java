package fun.fengwk.kkstudio.platform.environment.ide;

import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;

/**
 * IDE 视图授权的窄出口：owner 侧 {@code OPEN_VIEW}/{@code CLOSE_VIEW} 元数据事件，供数据面按 Daemon 代际维护受管 workspace 授权。
 *
 * <p>授权生命周期严格跟随 Daemon 的真实视图生命周期：视图创建即授权、视图关闭即撤销、代际结束即清空。数据面不得用「空闲超时」之类的时间
 * 推断替代真实的关闭事件，否则已关闭的视图仍能被访问。
 *
 * <p>实现必须是无副作用的本地内存登记；本接口只在 owner 节点被调用（跨节点元数据命令先单跳到 owner）。
 */
public interface IdeViewAuthorizationListener {

  /** 一个 IDE 视图已创建：授权其受管 workspace（同一代际下的多面板共用）。 */
  void viewOpened(IdeSessionIdentity identity, String viewId, String workspacePath);

  /** 一个 IDE 视图已关闭：撤销其受管 workspace 授权。 */
  void viewClosed(IdeSessionIdentity identity, String viewId);
}
