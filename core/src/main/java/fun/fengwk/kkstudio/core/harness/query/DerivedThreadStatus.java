package fun.fengwk.kkstudio.core.harness.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Thread 展示状态派生：仅依赖 PostgreSQL durable snapshot facts，不依赖 realtime projection。
 *
 * <pre>
 * RUNNING  : processor_token 非空且 processor_until &gt; now
 * WAITING  : 非 RUNNING，且 open Interaction / 同 epoch 非终态 Model|Tool / QUEUED input
 * RUNNABLE : runnable=true 且非 RUNNING/WAITING
 * UNBOUND  : 无以上执行事实且尚未绑定 head Entry
 * IDLE     : 其他
 * </pre>
 */
public final class DerivedThreadStatus {
  public static final String RUNNING = "RUNNING";
  public static final String WAITING = "WAITING";
  public static final String RUNNABLE = "RUNNABLE";
  public static final String UNBOUND = "UNBOUND";
  public static final String IDLE = "IDLE";

  private DerivedThreadStatus() {}

  public static String derive(HarnessQueryRow thread, Instant now) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(now, "now");
    if (isProcessing(thread.getProcessorToken(), thread.getProcessorUntil(), now)) {
      return RUNNING;
    }
    if (isTrue(thread.getHasOpenInteraction())
        || isTrue(thread.getHasActiveModel())
        || isTrue(thread.getHasActiveTool())
        || isTrue(thread.getHasQueuedInput())) {
      return WAITING;
    }
    if (isTrue(thread.getRunnable())) {
      return RUNNABLE;
    }
    if (thread.getHeadEntryId() == null) {
      return UNBOUND;
    }
    return IDLE;
  }

  public static boolean isProcessing(
      String processorToken, OffsetDateTime processorUntil, Instant now) {
    return processorToken != null
        && !processorToken.isBlank()
        && processorUntil != null
        && processorUntil.toInstant().isAfter(now);
  }

  private static boolean isTrue(Boolean value) {
    return Boolean.TRUE.equals(value);
  }
}
