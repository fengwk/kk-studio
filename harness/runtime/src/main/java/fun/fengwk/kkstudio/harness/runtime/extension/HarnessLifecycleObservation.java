package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import java.time.Instant;
import java.util.Objects;

/** 扩展可观测但不参与可靠状态恢复的 Harness 生命周期事实。 */
public sealed interface HarnessLifecycleObservation
    permits HarnessLifecycleObservation.TurnStarted,
        HarnessLifecycleObservation.AssistantCompleted,
        HarnessLifecycleObservation.RunTerminated,
        HarnessLifecycleObservation.CompactionCompleted,
        HarnessLifecycleObservation.ToolCompleted {

  record TurnStarted(long runId, long sessionId, int attempt, int turnIndex, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public TurnStarted {
      requireRunAndSessionIds(runId, sessionId);
      if (attempt <= 0 || turnIndex < 0) {
        throw new IllegalArgumentException("attempt must be positive and turnIndex non-negative");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record AssistantCompleted(
      long runId,
      long sessionId,
      int toolCallCount,
      ProviderStopReason stopReason,
      Instant occurredAt)
      implements HarnessLifecycleObservation {
    public AssistantCompleted {
      requireRunAndSessionIds(runId, sessionId);
      if (toolCallCount < 0) {
        throw new IllegalArgumentException("toolCallCount must not be negative");
      }
      stopReason = Objects.requireNonNull(stopReason, "stopReason");
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record RunTerminated(long runId, long sessionId, RunStatus status, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public RunTerminated {
      requireRunAndSessionIds(runId, sessionId);
      status = Objects.requireNonNull(status, "status");
      if (!status.terminal()) {
        throw new IllegalArgumentException("status must be terminal");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record CompactionCompleted(long runId, long sessionId, long firstKeptEntryId, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public CompactionCompleted {
      requireRunAndSessionIds(runId, sessionId);
      if (firstKeptEntryId <= 0) {
        throw new IllegalArgumentException("firstKeptEntryId must be positive");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record ToolCompleted(
      long invocationId, long runId, ToolInvocationStatus status, String error, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public ToolCompleted {
      if (invocationId <= 0 || runId <= 0) {
        throw new IllegalArgumentException("invocationId and runId must be positive");
      }
      status = Objects.requireNonNull(status, "status");
      if (!status.isTerminal()) {
        throw new IllegalArgumentException("status must be terminal");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  private static void requireRunAndSessionIds(long runId, long sessionId) {
    if (runId <= 0 || sessionId <= 0) {
      throw new IllegalArgumentException("runId and sessionId must be positive");
    }
  }
}
