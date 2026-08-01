package fun.fengwk.kkstudio.harness.runtime.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Instant;

class ModelUsageRecordTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** 合法 record 应通过所有不变量；草稿原样保留。 */
  @Test
  void acceptsValidRecord() {
    ModelUsageDraft draft = draft();
    ModelUsageRecord record = new ModelUsageRecord(1L, 11L, 21L, 31L, draft, NOW);

    assertEquals(1L, record.id());
    assertEquals(11L, record.sessionId());
    assertEquals(21L, record.threadId());
    assertEquals(31L, record.assistantEntryId());
    assertEquals(draft, record.draft());
    assertEquals(NOW, record.createdAt());
  }

  /** id 必须 >0；draft/createdAt 非空。 */
  @Test
  void rejectsInvalidIdentifiersAndReferences() {
    ModelUsageDraft draft = draft();
    assertThrows(IllegalArgumentException.class, () -> record(0L, 11L, 21L, 31L, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 0L, 21L, 31L, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 0L, 31L, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 21L, 0L, draft));
    assertThrows(NullPointerException.class, () -> record(1L, 11L, 21L, 31L, null));
    assertThrows(
        NullPointerException.class, () -> new ModelUsageRecord(1L, 11L, 21L, 31L, draft, null));
  }

  private static ModelUsageRecord record(
      long id, long sessionId, long threadId, long assistantEntryId, ModelUsageDraft draft) {
    return new ModelUsageRecord(id, sessionId, threadId, assistantEntryId, draft, NOW);
  }

  private static ModelUsageDraft draft() {
    ModelUsage usage = new ModelUsage(1, 2, 3, 4, 5, 6, 21);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelCost cost = ModelCost.calculate(pricing, usage);
    return new ModelUsageDraft(
        "provider",
        "model-x",
        ProviderType.OPENAI,
        PromptCacheMode.UNSUPPORTED,
        PromptCacheRetention.NONE,
        false,
        null,
        ProviderStopReason.COMPLETED,
        usage,
        cost,
        pricing,
        "req-1",
        "tier-reported",
        "{\"x\":1}");
  }
}
