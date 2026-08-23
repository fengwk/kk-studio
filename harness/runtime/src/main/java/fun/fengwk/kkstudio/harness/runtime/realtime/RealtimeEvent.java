package fun.fengwk.kkstudio.harness.runtime.realtime;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 只用于短期 UI 重放的非 durable projection event。
 *
 * <p>每个 subtype 都包含归属 Thread、durable subject 与创建时间。可重试的 Invocation projection 还必须携带 attempt，使
 * snapshot-first 客户端能够丢弃旧 attempt 的残留片段。PostgreSQL notification adapter 将其编码为 live overlay
 * envelope；Entry、Invocation 或 Thread 的恢复逻辑不得依赖该 event 是否存在。
 */
public sealed interface RealtimeEvent permits RealtimeEvent.ModelDelta, RealtimeEvent.ToolPartial {

  UUID threadId();

  Subject subject();

  RealtimeEventType type();

  Instant createdAt();

  /** durable subject 的不可变 identity：Invocation kind + durable id。 */
  record Subject(SubjectKind kind, UUID id) {
    public Subject {
      kind = Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(id, "id");
    }
  }

  /** Realtime projection 支持的 durable subject kind；wire 名称是稳定协议事实。 */
  enum SubjectKind {
    MODEL_INVOCATION,
    TOOL_INVOCATION
  }

  /**
   * 一条 Model Provider delta；sequence 在 attempt 内从 1 严格递增。最终完整 response 仍只进入 durable
   * ModelInvocation。
   */
  record ModelDelta(
      UUID threadId,
      UUID modelInvocationId,
      int attempt,
      long sequence,
      ProviderStreamEvent delta,
      Instant createdAt)
      implements RealtimeEvent {

    public ModelDelta {
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(modelInvocationId, "modelInvocationId");
      if (attempt <= 0) {
        throw new IllegalArgumentException("attempt must be positive");
      }
      if (sequence <= 0) {
        throw new IllegalArgumentException("sequence must be positive");
      }
      delta = Objects.requireNonNull(delta, "delta");
      createdAt =
          HarnessStoreTime.requireMillisecondPrecision(
              Objects.requireNonNull(createdAt, "createdAt"));
    }

    @Override
    public Subject subject() {
      return new Subject(SubjectKind.MODEL_INVOCATION, modelInvocationId);
    }

    @Override
    public RealtimeEventType type() {
      return RealtimeEventType.MODEL_DELTA;
    }
  }

  /** ToolInvocation attempt 发出的 best-effort partial result。 */
  record ToolPartial(
      UUID threadId, UUID toolInvocationId, int attempt, ToolResult partial, Instant createdAt)
      implements RealtimeEvent {

    public ToolPartial {
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(toolInvocationId, "toolInvocationId");
      if (attempt <= 0) {
        throw new IllegalArgumentException("attempt must be positive");
      }
      partial = Objects.requireNonNull(partial, "partial");
      createdAt =
          HarnessStoreTime.requireMillisecondPrecision(
              Objects.requireNonNull(createdAt, "createdAt"));
    }

    @Override
    public Subject subject() {
      return new Subject(SubjectKind.TOOL_INVOCATION, toolInvocationId);
    }

    @Override
    public RealtimeEventType type() {
      return RealtimeEventType.TOOL_PARTIAL;
    }
  }
}
