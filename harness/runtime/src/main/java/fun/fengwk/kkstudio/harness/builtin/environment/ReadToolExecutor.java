package fun.fengwk.kkstudio.harness.builtin.environment;

import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.function.Consumer;

/**
 * 统一 {@code read} 工具的执行窄端口。
 *
 * <p>Platform（{@code kkstudio:} 稳定地址）与可选 {@link BoundEnvironment}（本地文件系统路径）的路由由实现方承担，本模块不注册专用模型工具。
 */
@FunctionalInterface
public interface ReadToolExecutor {

  /**
   * 执行 {@code read} 请求。
   *
   * @param request 工具执行请求，非空
   * @param progress 非终止进度接收器，非空
   * @return 终态结果，非空
   * @throws InterruptedException 执行被取消
   */
  ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;
}
