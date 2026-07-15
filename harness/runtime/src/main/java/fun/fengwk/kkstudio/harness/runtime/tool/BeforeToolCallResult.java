package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import java.util.Objects;

/** beforeToolCall 可替换绑定 descriptor 或参数 JSON；permission 仅由末端边界产生。 */
public record BeforeToolCallResult(
    ToolBinding binding,
    String argumentsJson,
    PermissionAction permissionAction,
    PermissionPromptPreview permissionPromptPreview) {

  public BeforeToolCallResult(ToolBinding binding, String argumentsJson) {
    this(binding, argumentsJson, null, null);
  }

  public BeforeToolCallResult {
    binding = Objects.requireNonNull(binding, "binding");
    if (argumentsJson == null) {
      throw new IllegalArgumentException("argumentsJson must not be null");
    }
    if ((permissionAction == null) != (permissionPromptPreview == null)) {
      throw new IllegalArgumentException(
          "permissionAction and permissionPromptPreview must both be null or non-null");
    }
  }
}
