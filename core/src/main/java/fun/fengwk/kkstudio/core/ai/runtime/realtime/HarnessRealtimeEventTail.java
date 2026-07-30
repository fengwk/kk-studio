package fun.fengwk.kkstudio.core.ai.runtime.realtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Application-facing realtime event tail for SSE adapters.
 *
 * <p>Defines the transport-independent cursor contract used by Web SSE. Storage adapters implement
 * this boundary; callers depend only on Core types.
 */
public interface HarnessRealtimeEventTail {

  /**
   * Cursor positioned at the live edge of a lossy stream.
   *
   * <p>New SSE subscriptions deliberately start here rather than replaying retained Redis history:
   * PostgreSQL snapshot state repairs any missed durable or safe-stream data.
   */
  default String initialCursor(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    return "$";
  }

  /**
   * Reads records strictly after {@code afterId}. Use {@code "0-0"} (or blank/0) to start from the
   * beginning of the retained window. {@code "$"} starts at the live edge; use a previous internal
   * Redis cursor only within an already-open SSE connection. When {@code block} is positive and no
   * records are available, waits up to that duration.
   */
  List<Record> readAfter(long threadId, String afterId, int count, Duration block);

  /**
   * Normalizes SSE resume cursors to the canonical realtime stream cursor form {@code ms-seq}.
   *
   * <p>Null, the empty string, or {@code "0"} become {@code "0-0"} (start of retained window). Any
   * other value must already be a non-negative decimal pair separated by {@code '-'}.
   */
  static String normalizeAfterId(String afterId) {
    if (afterId == null || afterId.isEmpty() || "0".equals(afterId)) {
      return "0-0";
    }
    if ("$".equals(afterId)) {
      return afterId;
    }
    if (!afterId.matches("\\d+-\\d+")) {
      throw new IllegalArgumentException(
          "afterStreamId must be a realtime stream cursor (ms-seq) or 0: " + afterId);
    }
    return afterId;
  }

  /** One retained realtime event and its stream/cursor id. */
  record Record(String id, String payloadJson) {
    public Record {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(payloadJson, "payloadJson");
    }
  }
}
