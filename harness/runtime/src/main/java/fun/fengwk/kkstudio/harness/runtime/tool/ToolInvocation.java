package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Instant;
import java.util.Objects;

/** 持久 ToolInvocation 快照；thread 关联，不再绑定 Run。 */
public record ToolInvocation(
    long id,
    long threadId,
    long assistantEntryId,
    int ordinal,
    String toolCallId,
    String toolName,
    String toolVersion,
    ToolTargetType targetType,
    Long environmentId,
    String argumentsJson,
    ToolInvocationStatus status,
    PermissionAction permissionAction,
    ToolPermissionDecision permissionDecision,
    ToolSideEffect sideEffect,
    Instant deadlineAt,
    String leaseOwner,
    Instant leaseUntil,
    Instant cancelRequestedAt,
    String resultJson,
    String errorMessage,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant updatedAt) {

  public ToolInvocation {
    if (id <= 0 || threadId <= 0 || assistantEntryId <= 0) {
      throw new IllegalArgumentException("invocation ids must be positive");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    toolCallId = requireNonBlank(toolCallId, "toolCallId");
    toolName = requireNonBlank(toolName, "toolName");
    toolVersion = requireNonBlank(toolVersion, "toolVersion");
    if (toolCallId.length() > 256 || toolName.length() > 128 || toolVersion.length() > 128) {
      throw new IllegalArgumentException("tool identity exceeds persistent column bounds");
    }
    targetType = Objects.requireNonNull(targetType, "targetType");
    if (environmentId != null && environmentId <= 0) {
      throw new IllegalArgumentException("environmentId must be positive");
    }
    if (targetType == ToolTargetType.ENVIRONMENT && environmentId == null) {
      throw new IllegalArgumentException("ENVIRONMENT invocations require environmentId");
    }
    if (targetType != ToolTargetType.ENVIRONMENT && environmentId != null) {
      throw new IllegalArgumentException("environmentId is only valid for ENVIRONMENT tools");
    }
    argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
    status = Objects.requireNonNull(status, "status");
    permissionAction = Objects.requireNonNull(permissionAction, "permissionAction");
    sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
    deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
