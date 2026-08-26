package fun.fengwk.kkstudio.harness.runtime.permission;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.nio.file.Path;
import java.util.Objects;

/** 单次 Tool permission 评估所需的冻结路径与全局规则。 */
public record PermissionEvaluationContext(
    AgentToolId toolId, String argumentsJson, Path workdir, ToolSettings settings) {
  public PermissionEvaluationContext {
    toolId = Objects.requireNonNull(toolId, "toolId");
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("argumentsJson must not be blank");
    }
    workdir = Objects.requireNonNull(workdir, "workdir").toAbsolutePath().normalize();
    settings = Objects.requireNonNull(settings, "settings");
  }
}
