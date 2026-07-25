package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;

import java.time.Instant;
import java.util.Objects;

/** 扩展可观测但不参与可靠状态恢复的 Harness 生命周期事实。 */
public sealed interface HarnessLifecycleObservation
    permits HarnessLifecycleObservation.TurnStarted,
        HarnessLifecycleObservation.AssistantCompleted,
        HarnessLifecycleObservation.ThreadIdle,
        HarnessLifecycleObservation.CompactionCompleted,
        HarnessLifecycleObservation.ToolCompleted {

  record TurnStarted(long threadId, long sessionId, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public TurnStarted {
      requireThreadAndSessionIds(threadId, sessionId);
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record AssistantCompleted(
      long threadId,
      long sessionId,
      int toolCallCount,
      ProviderStopReason stopReason,
      Instant occurredAt)
      implements HarnessLifecycleObservation {
    public AssistantCompleted {
      requireThreadAndSessionIds(threadId, sessionId);
      if (toolCallCount < 0) {
        throw new IllegalArgumentException("toolCallCount must not be negative");
      }
      stopReason = Objects.requireNonNull(stopReason, "stopReason");
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record ThreadIdle(long threadId, long sessionId, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public ThreadIdle {
      requireThreadAndSessionIds(threadId, sessionId);
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record CompactionCompleted(
      long threadId, long sessionId, long firstKeptEntryId, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public CompactionCompleted {
      requireThreadAndSessionIds(threadId, sessionId);
      if (firstKeptEntryId <= 0) {
        throw new IllegalArgumentException("firstKeptEntryId must be positive");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record ToolCompleted(
      long invocationId, long threadId, InvocationStatus status, String error, Instant occurredAt)
      implements HarnessLifecycleObservation {
    public ToolCompleted {
      if (invocationId <= 0 || threadId <= 0) {
        throw new IllegalArgumentException("invocationId and threadId must be positive");
      }
      status = Objects.requireNonNull(status, "status");
      if (!status.isTerminal()) {
        throw new IllegalArgumentException("status must be terminal");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  private static void requireThreadAndSessionIds(long threadId, long sessionId) {
    if (threadId <= 0 || sessionId <= 0) {
      throw new IllegalArgumentException("threadId and sessionId must be positive");
    }
  }
}
