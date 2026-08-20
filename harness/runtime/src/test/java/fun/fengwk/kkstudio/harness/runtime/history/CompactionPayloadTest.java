package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** CompactionPayload 只持久化非空 summaryText；所有执行元数据由 enclosing turn 派生。 */
class CompactionPayloadTest {

  @Test
  void storesOnlySummaryText() {
    // 最小 payload 的值与 Entry type 都应稳定可重放。
    CompactionPayload payload = new CompactionPayload("structured summary");

    assertEquals("structured summary", payload.summaryText());
    assertEquals(EntryType.COMPACTION, payload.type());
  }

  @Test
  void rejectsMissingSummary() {
    // 空摘要不能成为 durable checkpoint。
    assertThrows(IllegalArgumentException.class, () -> new CompactionPayload(null));
    assertThrows(IllegalArgumentException.class, () -> new CompactionPayload(" "));
  }
}
