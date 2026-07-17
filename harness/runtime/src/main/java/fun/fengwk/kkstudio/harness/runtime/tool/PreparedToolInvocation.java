package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Instant;
import java.util.Objects;

/** Assistant -> Tool 事务写入前已完成 schema、interceptor 与 permission 评估的调用。 */
public record PreparedToolInvocation(
    long id,
    int ordinal,
    ToolBinding binding,
    ToolCall call,
    PermissionAction permissionAction,
    ToolInvocationStatus initialStatus,
    Instant deadlineAt,
    PermissionPromptPreview promptPreview,
    String resultJson,
    String errorMessage) {

  public PreparedToolInvocation {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    binding = Objects.requireNonNull(binding, "binding");
    call = Objects.requireNonNull(call, "call");
    if (call.id().length() > 256) {
      throw new IllegalArgumentException("toolCallId must fit persistent invocation column");
    }
    call.validateFor(binding.descriptor());
    permissionAction = Objects.requireNonNull(permissionAction, "permissionAction");
    initialStatus = Objects.requireNonNull(initialStatus, "initialStatus");
    deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    promptPreview = Objects.requireNonNull(promptPreview, "promptPreview");
    ToolInvocationStatus.PREPARING.requireTransitionTo(initialStatus);
    if (permissionAction == PermissionAction.ALLOW && initialStatus != ToolInvocationStatus.QUEUED
        || permissionAction == PermissionAction.ASK
            && initialStatus != ToolInvocationStatus.WAITING_APPROVAL
        || permissionAction == PermissionAction.DENY
            && initialStatus != ToolInvocationStatus.FAILED) {
      throw new IllegalArgumentException("permission action and initial status are inconsistent");
    }
    if (initialStatus == ToolInvocationStatus.FAILED
        && (resultJson == null || errorMessage == null || errorMessage.isBlank())) {
      throw new IllegalArgumentException("failed preparation requires resultJson and errorMessage");
    }
  }
}
