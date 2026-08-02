package fun.fengwk.kkstudio.core.ai.runtime.usage.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import fun.fengwk.kkstudio.core.ai.runtime.usage.store.mapper.ModelUsageRecordMapper;
import fun.fengwk.kkstudio.core.ai.runtime.usage.store.model.ModelUsageRecordDO;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Verifies that {@link PostgresqlModelUsageRecordStore} persists only immutable pricing/usage and
 * rebuilds {@link ModelCost} from {@link ModelPricing} + {@link ModelUsage} on read.
 */
class PostgresqlModelUsageRecordStoreTest {

  @Test
  void persistOmitsCostColumnsAndReconstructionMatchesCalculate() {
    ModelUsageRecordMapper mapper = Mockito.mock(ModelUsageRecordMapper.class);
    PostgresqlModelUsageRecordStore store = new PostgresqlModelUsageRecordStore(mapper);

    ModelPricing pricing = samplePricing();
    ModelUsage usage = new ModelUsage(1_000L, 200L, 50L, 0L, 0L, 0L, 1_250L);
    ModelCost expectedCost = ModelCost.calculate(pricing, usage);
    ModelUsageDraft draft =
        new ModelUsageDraft(
            "provider",
            "model-x",
            ProviderType.OPENAI,
            PromptCacheMode.AFFINITY,
            PromptCacheRetention.SHORT,
            true,
            "key-1",
            ProviderStopReason.COMPLETED,
            usage,
            expectedCost,
            pricing,
            "req-1",
            "default",
            "{}");
    ModelUsageRecord record =
        new ModelUsageRecord(11L, 22L, 33L, 44L, draft, Instant.parse("2026-01-01T00:00:00Z"));

    Mockito.when(mapper.insert(Mockito.any(ModelUsageRecordDO.class))).thenReturn(1);

    store.insert(record);

    ArgumentCaptor<ModelUsageRecordDO> captor = ArgumentCaptor.forClass(ModelUsageRecordDO.class);
    Mockito.verify(mapper).insert(captor.capture());
    ModelUsageRecordDO row = captor.getValue();

    assertNotNull(row.getCreateTime(), "created_at must be supplied explicitly as audit fact");
    assertEquals("USD", row.getPricingCurrency());
    assertEquals(Long.valueOf(1_000L), row.getUsageInputTokens());
    // No physical cost columns remain on the DO.
    Mockito.verifyNoMoreInteractions(mapper);
  }

  @Test
  void readRebuildsCostFromPricingAndUsage() {
    ModelUsageRecordMapper mapper = Mockito.mock(ModelUsageRecordMapper.class);
    PostgresqlModelUsageRecordStore store = new PostgresqlModelUsageRecordStore(mapper);

    ModelPricing pricing = samplePricing();
    ModelUsage usage = new ModelUsage(2_000L, 500L, 100L, 0L, 0L, 0L, 2_600L);
    ModelUsageRecordDO row = new ModelUsageRecordDO();
    row.setId(11L);
    row.setSessionId(22L);
    row.setThreadId(33L);
    row.setAssistantEntryId(44L);
    row.setProviderName("provider");
    row.setModelName("model-x");
    row.setProviderType("OPENAI");
    row.setPromptCacheMode("AFFINITY");
    row.setPromptCacheRetention("SHORT");
    row.setCacheEligible(true);
    row.setCacheAffinityKey("key-1");
    row.setStopReason("COMPLETED");
    row.setUsageInputTokens(usage.inputTokens());
    row.setUsageOutputTokens(usage.outputTokens());
    row.setUsageCacheReadTokens(usage.cacheReadTokens());
    row.setUsageCacheWriteTokens(usage.cacheWriteTokens());
    row.setUsageCacheWriteLongTokens(usage.cacheWriteLongTokens());
    row.setUsageReasoningTokens(usage.reasoningTokens());
    row.setUsageProviderTotalTokens(usage.providerTotalTokens());
    row.setPricingCurrency(pricing.currency());
    row.setPricingTier(pricing.pricingTier());
    row.setPricingServiceTier(pricing.serviceTier());
    row.setPricingServiceTierMultiplier(pricing.serviceTierMultiplier());
    row.setPricingVersion(pricing.version());
    row.setPricingInputPerMillionTokens(pricing.inputPerMillionTokens());
    row.setPricingOutputPerMillionTokens(pricing.outputPerMillionTokens());
    row.setPricingCacheReadPerMillionTokens(pricing.cacheReadPerMillionTokens());
    row.setPricingCacheWritePerMillionTokens(pricing.cacheWritePerMillionTokens());
    row.setPricingCacheWriteLongPerMillionTokens(pricing.cacheWriteLongPerMillionTokens());
    row.setPricingReasoningPerMillionTokens(pricing.reasoningPerMillionTokens());
    row.setRequestId("req-1");
    row.setReportedServiceTier("default");
    row.setRawUsageJson("{}");
    row.setCreateTime(
        OffsetDateTime.ofInstant(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));
    Mockito.when(mapper.findByAssistantEntryId(44L)).thenReturn(row);

    ModelUsageRecord record = store.findByAssistantEntryId(44L).orElseThrow();

    ModelCost expectedCost = ModelCost.calculate(pricing, usage);
    assertEquals(expectedCost, record.draft().cost());
    assertEquals(pricing, record.draft().pricing());
    assertEquals(usage, record.draft().usage());
    assertEquals("OPENAI", record.draft().providerType().name());
  }

  private static ModelPricing samplePricing() {
    return new ModelPricing(
        "USD",
        "tier",
        "default",
        new BigDecimal("1.000000000000"),
        "v1",
        new BigDecimal("1.000000000000"),
        new BigDecimal("2.000000000000"),
        new BigDecimal("3.000000000000"),
        new BigDecimal("4.000000000000"),
        new BigDecimal("5.000000000000"),
        new BigDecimal("6.000000000000"));
  }
}
