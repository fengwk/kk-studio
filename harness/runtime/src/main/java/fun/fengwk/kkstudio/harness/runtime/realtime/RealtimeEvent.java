package fun.fengwk.kkstudio.harness.runtime.realtime;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
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

  Subject subject();

  RealtimeEventType type();

  Instant createdAt();

  /** durable subject 的不可变 identity：Invocation kind + durable id。 */
  record Subject(SubjectKind kind, long id) {
    public Subject {
      kind = Objects.requireNonNull(kind, "kind");
      if (id <= 0) {
        throw new IllegalArgumentException("subject id must be positive");
      }
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
      long threadId,
      long modelInvocationId,
      int attempt,
      long sequence,
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
