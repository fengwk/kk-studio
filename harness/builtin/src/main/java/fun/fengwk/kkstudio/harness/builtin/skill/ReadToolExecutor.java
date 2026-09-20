package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;

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
   * @param listener 工具执行监听器，非空
   * @return 执行控制句柄，非空
   */
  ToolExecutionHandle read(ToolExecutionRequest request, ToolExecutionListener listener);
}
