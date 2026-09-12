package fun.fengwk.kkstudio.harness.runtime.permission;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.Objects;

/**
 * 单次 Tool permission 评估所需的冻结参数与全局规则。
 *
 * <p>目录不是独立评估输入：需要 workdir 的工具把目标目录放在自己的 {@code arguments.workdir} 中，evaluator 只读该字段。 没有 workdir
 * 语义的工具（例如 {@code load_skill}、MCP）不产生任何默认目录。
 */
public record PermissionEvaluationContext(
    AgentToolId toolId, String argumentsJson, ToolSettings settings) {

  public PermissionEvaluationContext {
    toolId = Objects.requireNonNull(toolId, "toolId");
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("argumentsJson must not be blank");
    }
    settings = Objects.requireNonNull(settings, "settings");
  }
}
