package fun.fengwk.kkstudio.core.harness.usage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.share.model.ModelUsageCostSummaryDTO;
import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class ModelUsageAggregationServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  private ModelUsageRecordStore recordStore;
  private ModelUsageAggregationServiceImpl service;

  @BeforeEach
  void setUp() {
    recordStore = mock(ModelUsageRecordStore.class);
    service = new ModelUsageAggregationServiceImpl(recordStore);
  }

  /** 多币种必须独立且稳定排序；cache 指标只看 eligible，waste 必须按 key 截断，不能全局抵消。 */
  @Test
  void aggregatesTokensCostsEligibleRatiosAndAffinityWaste() {
    ModelUsageRecord usdWrite =
        record(1L, eligible("USD", "alpha", usage(10, 2, 0, 100, 0, 3, 115)));
    ModelUsageRecord usdRead = record(2L, eligible("USD", "alpha", usage(5, 4, 60, 0, 0, 1, 70)));
    ModelUsageRecord eurOtherKey =
        record(3L, eligible("EUR", "beta", usage(5, 6, 70, 0, 0, 2, 83)));
    ModelUsageRecord nonEligible =
        record(4L, nonEligible("USD", usage(1000, 8, 500, 7, 9, 4, 1528)));
    when(recordStore.listByRunId(21L))
        .thenReturn(List.of(usdWrite, usdRead, eurOtherKey, nonEligible));

    ModelUsageSummaryDTO summary = service.summarizeRun(21L);

    assertEquals("run", summary.getScopeType());
    assertEquals("21", summary.getScopeId());
    assertEquals(4L, summary.getRecordCount());
    assertEquals(1020L, summary.getInputTokens());
    assertEquals(20L, summary.getOutputTokens());
    assertEquals(630L, summary.getCacheReadTokens());
    assertEquals(107L, summary.getCacheWriteTokens());
    assertEquals(9L, summary.getCacheWriteLongTokens());
    assertEquals(10L, summary.getReasoningTokens());
    assertEquals(1796L, summary.getProviderTotalTokens());
    assertEquals(3L, summary.getCacheEligibleRecordCount());
    assertEquals(2L, summary.getCacheHitRecordCount());
    assertDecimal("0.666667", summary.getCacheHitRatio());
    assertDecimal("0.866667", summary.getTokenReadRatio());
    assertEquals(40L, summary.getUnamortizedCacheWriteTokens());
    assertEquals(
        List.of("EUR", "USD"),
        summary.getCosts().stream().map(ModelUsageCostSummaryDTO::getCurrency).toList());
    assertCost(eurOtherKey.draft().cost(), summary.getCosts().get(0));
    assertCost(
        add(usdWrite.draft().cost(), usdRead.draft().cost(), nonEligible.draft().cost()),
        summary.getCosts().get(1));
  }

  /** null affinity key 必须以 record id 分桶，否则一条 read 会错误摊销另一条 write。 */
  @Test
  void treatsNullAffinityKeysAsIndependentRecords() {
    ModelUsageRecord write = record(11L, eligible("USD", null, usage(0, 0, 0, 50, 0, 0, 50)));
    ModelUsageRecord read = record(12L, eligible("USD", null, usage(0, 0, 50, 0, 0, 0, 50)));
    when(recordStore.listBySessionId(31L)).thenReturn(List.of(write, read));

    ModelUsageSummaryDTO summary = service.summarizeSession(31L);

    assertEquals(50L, summary.getUnamortizedCacheWriteTokens());
    assertDecimal("0.500000", summary.getCacheHitRatio());
    assertDecimal("1.000000", summary.getTokenReadRatio());
  }

  /** 三种空 scope 都返回带正确字符串 ID 的全零结果，并调用对应 store 查询。 */
  @Test
  void returnsZeroSummariesForEmptyScopes() {
    when(recordStore.listByRunId(41L)).thenReturn(List.of());
    when(recordStore.listBySessionId(42L)).thenReturn(List.of());
    when(recordStore.listByModelResourceId(43L)).thenReturn(List.of());

    assertEmpty(service.summarizeRun(41L), "run", "41");
    assertEmpty(service.summarizeSession(42L), "session", "42");
    assertEmpty(service.summarizeModel(43L), "model", "43");
    verify(recordStore).listByRunId(41L);
    verify(recordStore).listBySessionId(42L);
    verify(recordStore).listByModelResourceId(43L);
  }

  /** 任一 token 分类求和溢出必须立即失败，不能静默回绕。 */
  @Test
  void rejectsTokenOverflow() {
    ModelUsageRecord maximum =
        record(21L, nonEligible("USD", usage(Long.MAX_VALUE, 0, 0, 0, 0, 0, 0)));
    ModelUsageRecord one = record(22L, nonEligible("USD", usage(1, 0, 0, 0, 0, 0, 0)));
    when(recordStore.listByRunId(51L)).thenReturn(List.of(maximum, one));

    assertThrows(ArithmeticException.class, () -> service.summarizeRun(51L));
  }

  private static void assertEmpty(ModelUsageSummaryDTO summary, String scopeType, String scopeId) {
    assertEquals(scopeType, summary.getScopeType());
    assertEquals(scopeId, summary.getScopeId());
    assertEquals(0L, summary.getRecordCount());
    assertEquals(0L, summary.getInputTokens());
    assertEquals(0L, summary.getOutputTokens());
    assertEquals(0L, summary.getCacheReadTokens());
    assertEquals(0L, summary.getCacheWriteTokens());
    assertEquals(0L, summary.getCacheWriteLongTokens());
    assertEquals(0L, summary.getReasoningTokens());
    assertEquals(0L, summary.getProviderTotalTokens());
    assertEquals(0L, summary.getCacheEligibleRecordCount());
    assertEquals(0L, summary.getCacheHitRecordCount());
    assertDecimal("0.000000", summary.getCacheHitRatio());
    assertDecimal("0.000000", summary.getTokenReadRatio());
    assertEquals(0L, summary.getUnamortizedCacheWriteTokens());
    assertTrue(summary.getCosts().isEmpty());
  }

  private static ModelUsageRecord record(long id, ModelUsageDraft draft) {
    return new ModelUsageRecord(id, 11L, 21L, 100L + id, 1, (int) id, draft, NOW);
  }

  private static ModelUsageDraft eligible(String currency, String key, ModelUsage usage) {
    PromptCacheMode mode = key == null ? PromptCacheMode.AUTOMATIC : PromptCacheMode.AFFINITY;
    PromptCacheRetention retention =
        key == null ? PromptCacheRetention.NONE : PromptCacheRetention.SHORT;
    return draft(currency, mode, retention, true, key, usage);
  }

  private static ModelUsageDraft nonEligible(String currency, ModelUsage usage) {
    return draft(
        currency, PromptCacheMode.UNSUPPORTED, PromptCacheRetention.NONE, false, null, usage);
  }

  private static ModelUsageDraft draft(
      String currency,
      PromptCacheMode mode,
      PromptCacheRetention retention,
      boolean eligible,
      String key,
      ModelUsage usage) {
    ModelPricing pricing = pricing(currency);
    return new ModelUsageDraft(
        1L,
        2L,
        ProviderType.OPENAI,
        "model",
        mode,
        retention,
        eligible,
        key,
        ProviderStopReason.COMPLETED,
        usage,
        ModelCost.calculate(pricing, usage),
        pricing,
        null,
        null,
        "{}");
  }

  private static ModelPricing pricing(String currency) {
    return new ModelPricing(
        currency,
        "tier",
        "default",
        BigDecimal.ONE.setScale(12),
        "v1",
        new BigDecimal("1.000000000000"),
        new BigDecimal("2.000000000000"),
        new BigDecimal("3.000000000000"),
        new BigDecimal("4.000000000000"),
        new BigDecimal("5.000000000000"),
        new BigDecimal("6.000000000000"));
  }

  private static ModelUsage usage(
      long input,
      long output,
      long cacheRead,
      long cacheWrite,
      long cacheWriteLong,
      long reasoning,
      long providerTotal) {
    return new ModelUsage(
        input, output, cacheRead, cacheWrite, cacheWriteLong, reasoning, providerTotal);
  }

  private static ModelCost add(ModelCost first, ModelCost second, ModelCost third) {
    return new ModelCost(
        first.currency(),
        first.input().add(second.input()).add(third.input()),
        first.output().add(second.output()).add(third.output()),
        first.cacheRead().add(second.cacheRead()).add(third.cacheRead()),
        first.cacheWrite().add(second.cacheWrite()).add(third.cacheWrite()),
        first.cacheWriteLong().add(second.cacheWriteLong()).add(third.cacheWriteLong()),
        first.reasoning().add(second.reasoning()).add(third.reasoning()),
        first.total().add(second.total()).add(third.total()));
  }

  private static void assertCost(ModelCost expected, ModelUsageCostSummaryDTO actual) {
    assertEquals(expected.currency(), actual.getCurrency());
    assertDecimal(expected.input(), actual.getInput());
    assertDecimal(expected.output(), actual.getOutput());
    assertDecimal(expected.cacheRead(), actual.getCacheRead());
    assertDecimal(expected.cacheWrite(), actual.getCacheWrite());
    assertDecimal(expected.cacheWriteLong(), actual.getCacheWriteLong());
    assertDecimal(expected.reasoning(), actual.getReasoning());
    assertDecimal(expected.total(), actual.getTotal());
  }

  private static void assertDecimal(String expected, BigDecimal actual) {
    assertDecimal(new BigDecimal(expected), actual);
  }

  private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
    assertEquals(0, expected.compareTo(actual), () -> expected + " != " + actual);
  }
}
