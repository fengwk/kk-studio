package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

/** 可信编译期 afterToolCall 扩展；按 Extension Host 提供的顺序执行。 */
public interface AfterToolCallInterceptor {
  ToolResult intercept(AfterToolCallContext context);
}
