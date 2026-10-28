package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;

/**
 * IDE 视图控制服务：把 Studio API 的 view 请求翻译为 {@code IDE_COMMAND}，经既有 lease/READY 围栏下发到 owner。
 *
 * <p>claim 与绑定身份命令在发出前都经事件节点精确核对意图；目录/dirty 结果只由 Daemon 决定，Server 不伪造。claim 成功额外铸造一次性 bootstrap
 * 入口，响应中的目录/workspace/epoch 一律来自 Daemon。
 */
public interface EnvironmentIdeService {

  EnvironmentIdeViewDTO openView(EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request);

  EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request);

  void focusTerminal(
      EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request);

  void closeView(EnvironmentId environmentId, String viewId, EnvironmentIdeCloseViewDTO request);

  /** 显式分离当前 epoch 的使用证明；视图资源保留在 Daemon 的恢复窗口内。 */
  void detachView(EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request);

  /** owner 的当前视图快照；不创建视图，也不续租。 */
  EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId);
}
