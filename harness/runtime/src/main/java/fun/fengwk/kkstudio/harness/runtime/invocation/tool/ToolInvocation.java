package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable current state of one Tool invocation.
 *
 * <p>{@code attempt} counts executions actually accepted by the execution side; BUSY/OVERLOADED or
 * pre-execution local rejections do not increase it. {@code resultEntryId} links the ToolResult
 * Entry and is only present (possibly) on terminal states. Result and error are mutually exclusive
 * on every status; non-terminal states never carry terminal facts.
 */
public record ToolInvocation(
    long id,
    long modelInvocationId,
    long assistantEntryId,
    int ordinal,
    ToolInvocationRequest request,
    ToolInvocationStatus status,
    int attempt,
    ToolApproval approval,
    ToolResult result,
    ToolInvocationError error,
    Long resultEntryId,
    Instant createdAt,
    Instant updatedAt) {

  public ToolInvocation {
    if (id <= 0) {
      throw new IllegalArgumentException("invocation id must be positive");
    }
    if (modelInvocationId <= 0) {
      throw new IllegalArgumentException("modelInvocationId must be positive");
    }
    if (assistantEntryId <= 0) {
      throw new IllegalArgumentException("assistantEntryId must be positive");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    validateStatusFields(status, attempt, approval, result, error, resultEntryId, request);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  private static void validateStatusFields(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      Long resultEntryId,
      ToolInvocationRequest request) {
    boolean terminal = status.isTerminal();
    if (!terminal && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    if (terminal && resultEntryId != null && resultEntryId <= 0) {
      throw new IllegalArgumentException("terminal resultEntryId must be positive");
    }
    if (status == ToolInvocationStatus.WAITING_APPROVAL) {
      if (approval == null || !approval.required() || !approval.isUndecided()) {
        throw new IllegalArgumentException(
            "WAITING_APPROVAL requires a required undecided approval");
      }
      if (attempt != 0) {
        throw new IllegalArgumentException("WAITING_APPROVAL requires attempt 0");
      }
      requireNoTerminalFacts(status, result, error);
    } else if (status == ToolInvocationStatus.READY) {
      requireNoTerminalFacts(status, result, error);
      if (approval != null
          && (approval.isUndecided() || approval.decision() == ToolApprovalDecision.DENIED)) {
        throw new IllegalArgumentException("READY must not carry an undecided or denied approval");
      }
    } else if (status == ToolInvocationStatus.RUNNING) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("RUNNING requires a positive attempt");
      }
      requireNoTerminalFacts(status, result, error);
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.SUCCEEDED) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
      }
      if (result == null) {
        throw new IllegalArgumentException("SUCCEEDED requires a result");
      }
      if (!result.toolCallId().equals(request.call().id())) {
        throw new IllegalArgumentException(
            "SUCCEEDED result toolCallId must match the request call");
      }
      if (error != null) {
        throw new IllegalArgumentException("SUCCEEDED must not carry an error");
      }
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.UNKNOWN) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("UNKNOWN requires a positive attempt");
      }
      if (error == null) {
        throw new IllegalArgumentException("UNKNOWN requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException("UNKNOWN must not carry a result");
      }
      requireCompletedPreflightApproval(status, approval);
    } else {
      if (error == null) {
        throw new IllegalArgumentException(status + " requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException(status + " must not carry a result");
      }
    }
  }

  private static void requireCompletedPreflightApproval(
      ToolInvocationStatus status, ToolApproval approval) {
    if (approval == null
        || (approval.required() && approval.decision() != ToolApprovalDecision.ALLOWED)) {
      throw new IllegalArgumentException(status + " requires a completed preflight approval");
    }
  }

  private static void requireNoTerminalFacts(
      ToolInvocationStatus status, ToolResult result, ToolInvocationError error) {
    if (result != null || error != null) {
      throw new IllegalArgumentException(status + " must not carry terminal facts");
    }
  }
}
