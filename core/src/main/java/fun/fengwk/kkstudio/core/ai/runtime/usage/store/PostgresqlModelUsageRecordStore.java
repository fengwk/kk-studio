package fun.fengwk.kkstudio.core.ai.runtime.usage.store;

import org.springframework.stereotype.Repository;

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
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL 持久化模型调用账本（{@code harness_model_usage}）。 */
@Repository
public class PostgresqlModelUsageRecordStore implements ModelUsageRecordStore {

  private final ModelUsageRecordMapper mapper;

  public PostgresqlModelUsageRecordStore(ModelUsageRecordMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public int insert(ModelUsageRecord record) {
    Objects.requireNonNull(record, "record");
    int affected = mapper.insert(toDO(record));
    if (affected != 1) {
      throw new ConcurrentModificationException(
          "harness_model_usage insert affected " + affected + " rows but expected 1");
    }
    return affected;
  }

  @Override
  public Optional<ModelUsageRecord> findByAssistantEntryId(long assistantEntryId) {
    ModelUsageRecordDO row = mapper.findByAssistantEntryId(assistantEntryId);
    return Optional.ofNullable(row).map(this::toRecord);
  }

  @Override
  public List<ModelUsageRecord> listBySessionId(long sessionId) {
    return mapper.listBySessionId(sessionId).stream().map(this::toRecord).toList();
  }

  @Override
  public List<ModelUsageRecord> listByModel(String providerName, String modelName) {
    return mapper.listByModel(providerName, modelName).stream().map(this::toRecord).toList();
  }

  static ModelUsageRecordDO toDO(ModelUsageRecord record) {
    ModelUsageDraft draft = record.draft();
    ModelUsage usage = draft.usage();
    ModelPricing pricing = draft.pricing();

    ModelUsageRecordDO target = new ModelUsageRecordDO();
    target.setId(record.id());
    target.setSessionId(record.sessionId());
    target.setThreadId(record.threadId());
    target.setAssistantEntryId(record.assistantEntryId());
    target.setProviderName(draft.providerName());
    target.setModelName(draft.modelName());
    target.setProviderType(draft.providerType().name());
    target.setPromptCacheMode(draft.promptCacheMode().name());
    target.setPromptCacheRetention(draft.promptCacheRetention().name());
    target.setCacheEligible(draft.cacheEligible());
    target.setCacheAffinityKey(draft.cacheAffinityKey());
    target.setStopReason(draft.stopReason().name());
    target.setUsageInputTokens(usage.inputTokens());
    target.setUsageOutputTokens(usage.outputTokens());
    target.setUsageCacheReadTokens(usage.cacheReadTokens());
    target.setUsageCacheWriteTokens(usage.cacheWriteTokens());
    target.setUsageCacheWriteLongTokens(usage.cacheWriteLongTokens());
    target.setUsageReasoningTokens(usage.reasoningTokens());
    target.setUsageProviderTotalTokens(usage.providerTotalTokens());
    target.setPricingCurrency(pricing.currency());
    target.setPricingTier(pricing.pricingTier());
    target.setPricingServiceTier(pricing.serviceTier());
    target.setPricingServiceTierMultiplier(pricing.serviceTierMultiplier());
    target.setPricingVersion(pricing.version());
    target.setPricingInputPerMillionTokens(pricing.inputPerMillionTokens());
    target.setPricingOutputPerMillionTokens(pricing.outputPerMillionTokens());
    target.setPricingCacheReadPerMillionTokens(pricing.cacheReadPerMillionTokens());
    target.setPricingCacheWritePerMillionTokens(pricing.cacheWritePerMillionTokens());
    target.setPricingCacheWriteLongPerMillionTokens(pricing.cacheWriteLongPerMillionTokens());
    target.setPricingReasoningPerMillionTokens(pricing.reasoningPerMillionTokens());
    target.setRequestId(draft.requestId());
    target.setReportedServiceTier(draft.reportedServiceTier());
    target.setRawUsageJson(draft.rawUsageJson());
    target.setCreateTime(utc(record.createdAt()));
    return target;
  }

  private ModelUsageRecord toRecord(ModelUsageRecordDO source) {
    ModelUsage usage =
        new ModelUsage(
            source.getUsageInputTokens(),
            source.getUsageOutputTokens(),
            source.getUsageCacheReadTokens(),
            source.getUsageCacheWriteTokens(),
            source.getUsageCacheWriteLongTokens(),
            source.getUsageReasoningTokens(),
            source.getUsageProviderTotalTokens());
    ModelPricing pricing =
        new ModelPricing(
            source.getPricingCurrency(),
            source.getPricingTier(),
            source.getPricingServiceTier(),
            source.getPricingServiceTierMultiplier(),
            source.getPricingVersion(),
            source.getPricingInputPerMillionTokens(),
            source.getPricingOutputPerMillionTokens(),
            source.getPricingCacheReadPerMillionTokens(),
            source.getPricingCacheWritePerMillionTokens(),
            source.getPricingCacheWriteLongPerMillionTokens(),
            source.getPricingReasoningPerMillionTokens());
    ModelCost cost = ModelCost.calculate(pricing, usage);
    ModelUsageDraft draft =
        new ModelUsageDraft(
            source.getProviderName(),
            source.getModelName(),
            ProviderType.valueOf(source.getProviderType()),
            PromptCacheMode.valueOf(source.getPromptCacheMode()),
            PromptCacheRetention.valueOf(source.getPromptCacheRetention()),
            source.getCacheEligible(),
            source.getCacheAffinityKey(),
            ProviderStopReason.valueOf(source.getStopReason()),
            usage,
            cost,
            pricing,
            source.getRequestId(),
            source.getReportedServiceTier(),
            source.getRawUsageJson());
    return new ModelUsageRecord(
        source.getId(),
        source.getSessionId(),
        source.getThreadId(),
        source.getAssistantEntryId(),
        draft,
        instant(source.getCreateTime()));
  }

  private static OffsetDateTime utc(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
