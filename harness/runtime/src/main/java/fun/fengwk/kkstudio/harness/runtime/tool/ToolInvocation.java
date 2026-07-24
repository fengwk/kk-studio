package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.kernel.execution.Lease;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable durable aggregate mirroring one final-schema {@code harness_tool_invocation} row.
 * Worker state uses the shared Kernel {@link InvocationStatus} and {@link Lease}; permission or
 * external-input waiting is represented by a separate Interaction fact rather than Tool-specific
 * states.
 */
public record ToolInvocation(
    long id,
    long threadId,
    long assistantEntryId,
    int ordinal,
    String toolCallId,
    ToolDescriptor descriptor,
    String argumentsJson,
    ToolExecutionLocation location,
    String environmentName,
    long executionEpoch,
    InvocationStatus status,
    int attempt,
    Instant nextAttemptAt,
    Lease workerLease,
    Instant deadlineAt,
    Instant lastActivityAt,
    ToolResult result,
    ToolInvocationError error,
    Instant appliedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {

  public ToolInvocation {
    if (id <= 0 || threadId <= 0 || assistantEntryId <= 0) {
      throw new IllegalArgumentException("invocation ids must be positive");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    toolCallId = requireNonBlank(toolCallId, "toolCallId");
    if (toolCallId.length() > 256) {
      throw new IllegalArgumentException("toolCallId must fit persistent column bounds");
    }
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
    location = Objects.requireNonNull(location, "location");
    validateLocation(location, environmentName);
    if (executionEpoch < 0) {
      throw new IllegalArgumentException("executionEpoch must be non-negative");
    }
    status = Objects.requireNonNull(status, "status");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (descriptor.executionLocation() != location) {
      throw new IllegalArgumentException("descriptor location must equal invocation location");
    }
    validateShape(
        status,
        nextAttemptAt,
        workerLease,
        deadlineAt,
        lastActivityAt,
        result,
        error,
        appliedAt,
        createdAt,
        startedAt,
        finishedAt);
  }

  public boolean isTerminalUnapplied() {
    return status.isTerminal() && appliedAt == null;
  }

  public boolean hasActiveWorkerAt(Instant now) {
    Objects.requireNonNull(now, "now");
    return status == InvocationStatus.RUNNING && workerLease.isActiveAt(now);
  }

  public boolean isDispatchableAt(Instant now) {
    Objects.requireNonNull(now, "now");
    return status == InvocationStatus.QUEUED
        || (status == InvocationStatus.RETRY_WAIT && !nextAttemptAt.isAfter(now));
  }

  public String toolName() {
    return descriptor.name();
  }

  public String toolVersion() {
    return descriptor.version();
  }

  private static void validateLocation(ToolExecutionLocation location, String environmentName) {
    if (environmentName != null && (environmentName.isBlank() || environmentName.length() > 128)) {
      throw new IllegalArgumentException("environmentName must be non-blank and <= 128 chars");
    }
    if (location == ToolExecutionLocation.ENVIRONMENT && environmentName == null) {
      throw new IllegalArgumentException("ENVIRONMENT invocations require environmentName");
    }
    if (location == ToolExecutionLocation.PLATFORM && environmentName != null) {
      throw new IllegalArgumentException("PLATFORM invocations must not have environmentName");
    }
  }

  private static void validateShape(
      InvocationStatus status,
      Instant nextAttemptAt,
      Lease workerLease,
      Instant deadlineAt,
      Instant lastActivityAt,
      ToolResult result,
      ToolInvocationError error,
      Instant appliedAt,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt) {
    if (startedAt != null && startedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("startedAt must be >= createdAt");
    }
    if (deadlineAt != null && (startedAt == null || !deadlineAt.isAfter(startedAt))) {
      throw new IllegalArgumentException("deadlineAt must be strictly after startedAt");
    }
    if (workerLease != null && (startedAt == null || !workerLease.until().isAfter(startedAt))) {
      throw new IllegalArgumentException("workerLease.until must be strictly after startedAt");
    }
    if (lastActivityAt != null && (startedAt == null || lastActivityAt.isBefore(startedAt))) {
      throw new IllegalArgumentException("lastActivityAt must be >= startedAt");
    }
    if (finishedAt != null) {
      Instant floor = startedAt == null ? createdAt : startedAt;
      if (finishedAt.isBefore(floor)) {
        throw new IllegalArgumentException("finishedAt must be >= startedAt or createdAt");
      }
      if (lastActivityAt != null && lastActivityAt.isAfter(finishedAt)) {
        throw new IllegalArgumentException("lastActivityAt must be <= finishedAt");
      }
    }
    if (nextAttemptAt != null) {
      if (deadlineAt == null || lastActivityAt == null) {
        throw new IllegalArgumentException("nextAttemptAt requires deadlineAt and lastActivityAt");
      }
      if (!nextAttemptAt.isAfter(lastActivityAt) || !nextAttemptAt.isBefore(deadlineAt)) {
        throw new IllegalArgumentException(
            "nextAttemptAt must be after lastActivityAt and before deadlineAt");
      }
    }
    if (appliedAt != null) {
      if (!status.isTerminal() || finishedAt == null || appliedAt.isBefore(finishedAt)) {
        throw new IllegalArgumentException(
            "appliedAt requires terminal status and must be >= finishedAt");
      }
    }

    switch (status) {
      case QUEUED -> {
        requireNull("QUEUED", "nextAttemptAt", nextAttemptAt);
        requireNull("QUEUED", "workerLease", workerLease);
        requireNull("QUEUED", "startedAt", startedAt);
        requireNull("QUEUED", "deadlineAt", deadlineAt);
        requireNull("QUEUED", "lastActivityAt", lastActivityAt);
        requireNull("QUEUED", "finishedAt", finishedAt);
        requireNull("QUEUED", "result", result);
        requireNull("QUEUED", "error", error);
      }
      case RUNNING -> {
        requireNull("RUNNING", "nextAttemptAt", nextAttemptAt);
        requireNull("RUNNING", "finishedAt", finishedAt);
        requireNull("RUNNING", "result", result);
        requireNull("RUNNING", "error", error);
        requireRunningClocks(workerLease, deadlineAt, lastActivityAt);
      }
      case RETRY_WAIT -> {
        requireNull("RETRY_WAIT", "workerLease", workerLease);
        requireNull("RETRY_WAIT", "finishedAt", finishedAt);
        requireNull("RETRY_WAIT", "result", result);
        requireNull("RETRY_WAIT", "error", error);
        if (nextAttemptAt == null) {
          throw new IllegalArgumentException("RETRY_WAIT requires nextAttemptAt");
        }
      }
      case SUCCEEDED -> {
        requireTerminalShape(nextAttemptAt, workerLease, finishedAt);
        requireNull("SUCCEEDED", "error", error);
        if (result == null) {
          throw new IllegalArgumentException("SUCCEEDED requires result");
        }
        requireExecutedClocks(deadlineAt, lastActivityAt);
      }
      case FAILED, UNKNOWN -> {
        requireTerminalShape(nextAttemptAt, workerLease, finishedAt);
        requireNull(status.name(), "result", result);
        if (error == null) {
          throw new IllegalArgumentException(status + " requires error");
        }
        requireExecutedClocks(deadlineAt, lastActivityAt);
      }
      case CANCELLED -> {
        requireTerminalShape(nextAttemptAt, workerLease, finishedAt);
        requireNull("CANCELLED", "result", result);
        requireNull("CANCELLED", "error", error);
        boolean noClocks = startedAt == null && deadlineAt == null && lastActivityAt == null;
        boolean fullClocks = startedAt != null && deadlineAt != null && lastActivityAt != null;
        if (!noClocks && !fullClocks) {
          throw new IllegalArgumentException(
              "CANCELLED requires no execution clocks or the full running triad");
        }
      }
    }
  }

  private static void requireRunningClocks(
      Lease workerLease, Instant deadlineAt, Instant lastActivityAt) {
    if (workerLease == null || deadlineAt == null || lastActivityAt == null) {
      throw new IllegalArgumentException(
          "RUNNING requires workerLease, deadlineAt and lastActivityAt");
    }
  }

  private static void requireExecutedClocks(Instant deadlineAt, Instant lastActivityAt) {
    if (deadlineAt == null || lastActivityAt == null) {
      throw new IllegalArgumentException(
          "executed terminal status requires deadlineAt and lastActivityAt");
    }
  }

  private static void requireTerminalShape(
      Instant nextAttemptAt, Lease workerLease, Instant finishedAt) {
    requireNull("terminal", "nextAttemptAt", nextAttemptAt);
    requireNull("terminal", "workerLease", workerLease);
    if (finishedAt == null) {
      throw new IllegalArgumentException("terminal status requires finishedAt");
    }
  }

  private static void requireNull(String status, String name, Object value) {
    if (value != null) {
      throw new IllegalArgumentException(status + " must have null " + name);
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
