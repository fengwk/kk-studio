package fun.fengwk.kkstudio.harness.runtime.permission;

import java.nio.file.Path;
import java.util.Objects;

/** 单次 Tool permission 评估所需的冻结路径与全局规则。 */
public record PermissionEvaluationContext(
    String toolName, String argumentsJson, Path workdir, ToolSettings settings) {
  public PermissionEvaluationContext {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("argumentsJson must not be blank");
    }
    workdir = Objects.requireNonNull(workdir, "workdir").toAbsolutePath().normalize();
    settings = Objects.requireNonNull(settings, "settings");
  }
}
