package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.Objects;

/**
 * 单次 Tool permission 评估所需的冻结参数与全局规则。
 *
 * <p>工具身份是模型可见 tool name。目录不是独立评估输入：需要 workdir 的工具把目标目录放在自己的 {@code arguments.workdir} 中，evaluator
 * 只读该字段。 没有 workdir 语义的工具（例如 {@code task}、MCP）不产生任何默认目录。
 */
public record PermissionEvaluationContext(
    String toolName, String argumentsJson, ToolSettings settings) {

  public PermissionEvaluationContext {
    toolName = Objects.requireNonNull(toolName, "toolName");
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("argumentsJson must not be blank");
    }
    settings = Objects.requireNonNull(settings, "settings");
  }
}
