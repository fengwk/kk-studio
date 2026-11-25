package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;

/**
 * IDE 视图控制服务：把 Studio API 的 view 请求翻译为 {@code IDE_COMMAND}，经既有 lease/READY 围栏下发到 owner。
 *
 * <p>claim 与绑定身份命令在发出前都经事件节点精确核对意图；目录/dirty 结果只由 Daemon 决定，Server 不伪造。claim 成功额外铸造一次性 bootstrap
 * 入口，响应中的目录/workspace/epoch 一律来自 Daemon。目录可缺省（零根视图），此时绑定仍由 workspacePath 与正 bindingEpoch 确认。
 */
public interface EnvironmentIdeService {

  EnvironmentIdeViewDTO openView(
      EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request, StudioPrincipal principal);

  EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeChangeDirectoryDTO request,
      StudioPrincipal principal);

  void focusTerminal(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeViewBindingDTO request,
      StudioPrincipal principal);

  void openAttachment(
      EnvironmentId environmentId,
      String viewId,
      String sessionId,
      String resourceId,
      StudioPrincipal principal,
      EnvironmentIdeViewBindingDTO request);

  void collapseMobile(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeViewBindingDTO request,
      StudioPrincipal principal);

  void closeView(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeCloseViewDTO request,
      StudioPrincipal principal);

  /** 纯读单个视图：只返回当前 owner 的权威资源和绑定，不创建、不续租、不发票据。 */
  EnvironmentIdeViewSnapshotDTO readView(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeReadViewDTO request,
      StudioPrincipal principal);
}
