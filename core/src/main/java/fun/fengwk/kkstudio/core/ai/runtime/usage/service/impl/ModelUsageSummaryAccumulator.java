package fun.fengwk.kkstudio.core.ai.runtime.usage.service.impl;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.share.ai.runtime.ModelUsageCostSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelUsageSummaryDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 单个 usage scope 的内存聚合器。 */
final class ModelUsageSummaryAccumulator {

  private static final int RATIO_SCALE = 6;
  private static final BigDecimal ZERO_RATIO = BigDecimal.ZERO.setScale(RATIO_SCALE);

  private final String scopeType;
  private final String scopeId;
  private final Map<String, CostAccumulator> costs = new TreeMap<>();
  private final Map<CacheBucketKey, CacheBucket> cacheBuckets = new HashMap<>();
  private long recordCount;
  private long inputTokens;
  private long outputTokens;
  private long cacheReadTokens;
  private long cacheWriteTokens;
  private long cacheWriteLongTokens;
  private long reasoningTokens;
  private long providerTotalTokens;
  private long cacheEligibleRecordCount;
  private long cacheHitRecordCount;
  private long eligibleCacheReadTokens;
  private long eligibleReadDenominatorTokens;

  ModelUsageSummaryAccumulator(String scopeType, long scopeId) {
    this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
    this.scopeId = Long.toString(scopeId);
  }

  void add(ModelUsageRecord record) {
    Objects.requireNonNull(record, "record");
    recordCount = Math.addExact(recordCount, 1L);
    ModelUsageDraft draft = record.draft();
    ModelUsage usage = draft.usage();
    inputTokens = Math.addExact(inputTokens, usage.inputTokens());
    outputTokens = Math.addExact(outputTokens, usage.outputTokens());
    cacheReadTokens = Math.addExact(cacheReadTokens, usage.cacheReadTokens());
    cacheWriteTokens = Math.addExact(cacheWriteTokens, usage.cacheWriteTokens());
    cacheWriteLongTokens = Math.addExact(cacheWriteLongTokens, usage.cacheWriteLongTokens());
    reasoningTokens = Math.addExact(reasoningTokens, usage.reasoningTokens());
    providerTotalTokens = Math.addExact(providerTotalTokens, usage.providerTotalTokens());
    costs
        .computeIfAbsent(draft.cost().currency(), ignored -> new CostAccumulator())
        .add(draft.cost());
    if (draft.cacheEligible()) {
      addEligible(record, usage);
    }
  }

  ModelUsageSummaryDTO toSummary() {
    ModelUsageSummaryDTO summary = new ModelUsageSummaryDTO();
    summary.setScopeType(scopeType);
    summary.setScopeId(scopeId);
    summary.setRecordCount(recordCount);
    summary.setInputTokens(inputTokens);
    summary.setOutputTokens(outputTokens);
    summary.setCacheReadTokens(cacheReadTokens);
    summary.setCacheWriteTokens(cacheWriteTokens);
    summary.setCacheWriteLongTokens(cacheWriteLongTokens);
    summary.setReasoningTokens(reasoningTokens);
    summary.setProviderTotalTokens(providerTotalTokens);
    summary.setCacheEligibleRecordCount(cacheEligibleRecordCount);
    summary.setCacheHitRecordCount(cacheHitRecordCount);
    summary.setCacheHitRatio(ratio(cacheHitRecordCount, cacheEligibleRecordCount));
    summary.setTokenReadRatio(ratio(eligibleCacheReadTokens, eligibleReadDenominatorTokens));
    summary.setUnamortizedCacheWriteTokens(unamortizedCacheWriteTokens());
    summary.setCosts(
        costs.entrySet().stream()
            .map(entry -> entry.getValue().toSummary(entry.getKey()))
            .toList());
    return summary;
  }

  private void addEligible(ModelUsageRecord record, ModelUsage usage) {
    cacheEligibleRecordCount = Math.addExact(cacheEligibleRecordCount, 1L);
    if (usage.cacheReadTokens() > 0) {
      cacheHitRecordCount = Math.addExact(cacheHitRecordCount, 1L);
    }
    eligibleCacheReadTokens = Math.addExact(eligibleCacheReadTokens, usage.cacheReadTokens());
    long readDenominator = Math.addExact(usage.inputTokens(), usage.cacheReadTokens());
    eligibleReadDenominatorTokens = Math.addExact(eligibleReadDenominatorTokens, readDenominator);
    CacheBucketKey bucketKey =
        record.draft().cacheAffinityKey() == null
            ? CacheBucketKey.forRecord(record.id())
            : CacheBucketKey.forAffinity(record.draft().cacheAffinityKey());
    cacheBuckets.computeIfAbsent(bucketKey, ignored -> new CacheBucket()).add(usage);
  }

  private long unamortizedCacheWriteTokens() {
    long total = 0L;
    for (CacheBucket bucket : cacheBuckets.values()) {
      total = Math.addExact(total, bucket.unamortizedWrites());
    }
    return total;
  }

  private static BigDecimal ratio(long numerator, long denominator) {
    if (denominator == 0L) {
      return ZERO_RATIO;
    }
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_UP);
  }

  private record CacheBucketKey(String affinityKey, long recordId) {
    static CacheBucketKey forAffinity(String affinityKey) {
      return new CacheBucketKey(affinityKey, 0L);
    }

    static CacheBucketKey forRecord(long recordId) {
      return new CacheBucketKey(null, recordId);
    }
  }

  private static final class CacheBucket {
    private long writes;
    private long reads;

    void add(ModelUsage usage) {
      long recordWrites = Math.addExact(usage.cacheWriteTokens(), usage.cacheWriteLongTokens());
      writes = Math.addExact(writes, recordWrites);
      reads = Math.addExact(reads, usage.cacheReadTokens());
    }

    long unamortizedWrites() {
      return writes > reads ? writes - reads : 0L;
    }
  }

  private static final class CostAccumulator {
    private BigDecimal input = BigDecimal.ZERO;
    private BigDecimal output = BigDecimal.ZERO;
    private BigDecimal cacheRead = BigDecimal.ZERO;
    private BigDecimal cacheWrite = BigDecimal.ZERO;
    private BigDecimal cacheWriteLong = BigDecimal.ZERO;
    private BigDecimal reasoning = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;

    void add(ModelCost cost) {
      input = input.add(cost.input());
      output = output.add(cost.output());
      cacheRead = cacheRead.add(cost.cacheRead());
      cacheWrite = cacheWrite.add(cost.cacheWrite());
      cacheWriteLong = cacheWriteLong.add(cost.cacheWriteLong());
      reasoning = reasoning.add(cost.reasoning());
      total = total.add(cost.total());
    }

    ModelUsageCostSummaryDTO toSummary(String currency) {
      ModelUsageCostSummaryDTO summary = new ModelUsageCostSummaryDTO();
      summary.setCurrency(currency);
      summary.setInput(input);
      summary.setOutput(output);
      summary.setCacheRead(cacheRead);
      summary.setCacheWrite(cacheWrite);
      summary.setCacheWriteLong(cacheWriteLong);
      summary.setReasoning(reasoning);
      summary.setTotal(total);
      return summary;
    }
  }
}
