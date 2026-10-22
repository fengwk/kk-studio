package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

/**
 * IDE 视图控制服务：把 Studio API 的 view 请求翻译为 {@code IDE_COMMAND}，经既有 lease/READY 围栏下发到 Daemon。
 *
 * <p>目录是否可访问、文档是否 dirty 一律由 Daemon 按 Node 标准失败回执，Server 不伪造结果；{@code OPEN_VIEW} 额外铸造一次性 bootstrap
 * 入口。
 */
public interface EnvironmentIdeService {

  EnvironmentIdeViewDTO openView(EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request);

  EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request);

  void focusTerminal(EnvironmentId environmentId, String viewId);

  void closeView(EnvironmentId environmentId, String viewId);
}
