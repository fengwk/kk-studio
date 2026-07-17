package fun.fengwk.kkstudio.harness.runtime.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Instant;

class ModelUsageRecordTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** 合法 record 应通过所有不变量；草稿原样保留。 */
  @Test
  void acceptsValidRecord() {
    ModelUsageDraft draft = draft();
    ModelUsageRecord record = new ModelUsageRecord(1L, 11L, 21L, 31L, 1, 0, draft, NOW);

    assertEquals(1L, record.id());
    assertEquals(11L, record.sessionId());
    assertEquals(21L, record.runId());
    assertEquals(31L, record.assistantEntryId());
    assertEquals(1, record.attempt());
    assertEquals(0, record.turnIndex());
    assertEquals(draft, record.draft());
    assertEquals(NOW, record.createdAt());
  }

  /** turnIndex 必须 >=0；attempt 必须 >0；其它 id 必须 >0；draft/createdAt 非空。 */
  @Test
  void rejectsInvalidIdentifiersAndReferences() {
    ModelUsageDraft draft = draft();
    assertThrows(IllegalArgumentException.class, () -> record(0L, 11L, 21L, 31L, 1, 0, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 0L, 21L, 31L, 1, 0, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 0L, 31L, 1, 0, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 21L, 0L, 1, 0, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 21L, 31L, 0, 0, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 21L, 31L, -1, 0, draft));
    assertThrows(IllegalArgumentException.class, () -> record(1L, 11L, 21L, 31L, 1, -1, draft));
    assertThrows(NullPointerException.class, () -> record(1L, 11L, 21L, 31L, 1, 0, null));
    assertThrows(
        NullPointerException.class,
        () -> new ModelUsageRecord(1L, 11L, 21L, 31L, 1, 0, draft, null));
  }

  private static ModelUsageRecord record(
      long id,
      long sessionId,
      long runId,
      long assistantEntryId,
      int attempt,
      int turnIndex,
      ModelUsageDraft draft) {
    return new ModelUsageRecord(
        id, sessionId, runId, assistantEntryId, attempt, turnIndex, draft, NOW);
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
        101L,
        202L,
        ProviderType.OPENAI,
        "model-x",
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
