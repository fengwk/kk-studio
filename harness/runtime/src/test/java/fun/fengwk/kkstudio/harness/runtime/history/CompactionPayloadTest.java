package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;

/** CompactionPayload 只持久化非空 summaryText 与可选的真实模型输出 metadata；其它执行元数据由 enclosing turn 派生。 */
class CompactionPayloadTest {

  @Test
  void storesSummaryTextAndOptionalProviderMetadata() {
    // 纯摘要组装没有模型用量事实：metadata 必须显式为 null，而不是伪造 0 用量。
    CompactionPayload bare = new CompactionPayload("structured summary", null);

    assertEquals("structured summary", bare.summaryText());
    assertNull(bare.assistantMetadata());
    assertEquals(EntryType.COMPACTION, bare.type());

    // 正式模型输出携带原始 provider metadata。
    AssistantMessageMetadata metadata =
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(3L, 4L, 0L, 0L, 0L, 0L, 7L), 12L);
    assertEquals(metadata, new CompactionPayload("summary", metadata).assistantMetadata());
  }

  @Test
  void rejectsMissingSummary() {
    // 空摘要不能成为 durable checkpoint。
    assertThrows(IllegalArgumentException.class, () -> new CompactionPayload(null, null));
    assertThrows(IllegalArgumentException.class, () -> new CompactionPayload(" ", null));
  }
}
