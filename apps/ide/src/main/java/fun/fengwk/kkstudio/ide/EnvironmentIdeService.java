package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;

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

  void collapseMobile(
      EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request);

  EnvironmentIdeDirectoryListingDTO listDirectories(EnvironmentId environmentId, String path);

  void closeView(EnvironmentId environmentId, String viewId, EnvironmentIdeCloseViewDTO request);

  /** owner 的当前视图快照；不创建视图，也不续租。 */
  EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId);
}
