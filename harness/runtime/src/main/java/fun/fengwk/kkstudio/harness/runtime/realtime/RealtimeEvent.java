package fun.fengwk.kkstudio.harness.runtime.realtime;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;

/**
 * 只用于短期 UI 重放的非 durable projection event。
 *
 * <p>每个 subtype 都包含归属 Thread、durable subject 与创建时间。可重试的 Invocation projection 还必须携带 attempt，使
 * snapshot-first 客户端能够丢弃旧 attempt 的残留片段。Redis adapter 将其编码为 stream envelope；Entry、Invocation 或
 * Thread 的恢复逻辑不得依赖该 event 是否存在。
 */
public sealed interface RealtimeEvent permits RealtimeEvent.ModelDelta, RealtimeEvent.ToolPartial {

  long threadId();

  ExecutionTarget subject();

  RealtimeEventType type();

  Instant createdAt();

  /** 一条 Model Provider delta。最终完整 response 仍只进入 durable ModelInvocation。 */
  record ModelDelta(
      long threadId,
      long modelInvocationId,
      int attempt,
      ProviderStreamEvent delta,
      Instant createdAt)
      implements RealtimeEvent {

    public ModelDelta {
      if (threadId <= 0) {
        throw new IllegalArgumentException("threadId must be positive");
      }
      if (modelInvocationId <= 0) {
        throw new IllegalArgumentException("modelInvocationId must be positive");
      }
      if (attempt <= 0) {
        throw new IllegalArgumentException("attempt must be positive");
      }
      delta = Objects.requireNonNull(delta, "delta");
      createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public ExecutionTarget subject() {
      return new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, modelInvocationId);
    }

    @Override
    public RealtimeEventType type() {
      return RealtimeEventType.MODEL_DELTA;
    }
  }

  /** One best-effort partial result emitted by a ToolInvocation attempt. */
  record ToolPartial(
      long threadId, long toolInvocationId, int attempt, ToolResult partial, Instant createdAt)
      implements RealtimeEvent {

    public ToolPartial {
      if (threadId <= 0 || toolInvocationId <= 0) {
        throw new IllegalArgumentException("threadId and toolInvocationId must be positive");
      }
      if (attempt <= 0) {
        throw new IllegalArgumentException("attempt must be positive");
      }
      partial = Objects.requireNonNull(partial, "partial");
      createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public ExecutionTarget subject() {
      return new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, toolInvocationId);
    }

    @Override
    public RealtimeEventType type() {
      return RealtimeEventType.TOOL_PARTIAL;
    }
  }
}
