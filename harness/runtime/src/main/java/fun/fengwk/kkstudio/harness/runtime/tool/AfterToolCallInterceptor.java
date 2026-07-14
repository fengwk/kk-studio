package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

/** 可信编译期 afterToolCall 扩展；priority 小者先执行。 */
public interface AfterToolCallInterceptor {
  default int priority() {
    return 0;
  }

  ToolResult intercept(AfterToolCallContext context);
}
