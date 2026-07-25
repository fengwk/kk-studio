package fun.fengwk.kkstudio.core.harness.realtime;

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
   * Reads records strictly after {@code afterId}. Use {@code "0-0"} (or blank/0) to start from the
   * beginning of the retained window; use a previous SSE id to resume. When {@code block} is
   * positive and no records are available, waits up to that duration.
   */
  List<Record> readAfter(long threadId, String afterId, int count, Duration block);

  /**
   * Normalizes SSE resume cursors to the canonical realtime stream cursor form {@code ms-seq}.
   *
   * <p>Blank, null, or {@code "0"} become {@code "0-0"} (start of retained window). Any other value
   * must already be a positive decimal pair separated by {@code '-'}.
   */
  static String normalizeAfterId(String afterId) {
    if (afterId == null || afterId.isBlank() || "0".equals(afterId.trim())) {
      return "0-0";
    }
    String trimmed = afterId.trim();
    if (!trimmed.matches("\\d+-\\d+")) {
      throw new IllegalArgumentException(
          "afterEventId must be a realtime stream cursor (ms-seq) or 0: " + afterId);
    }
    return trimmed;
  }

  /** One retained realtime event and its stream/cursor id. */
  record Record(String id, String payloadJson) {
    public Record {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(payloadJson, "payloadJson");
    }
  }
}
