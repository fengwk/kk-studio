package fun.fengwk.kkstudio.core.harness.usage.store;

import fun.fengwk.kkstudio.core.harness.usage.store.mapper.ModelUsageRecordMapper;
import fun.fengwk.kkstudio.core.harness.usage.store.model.ModelUsageRecordDO;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** MySQL/H2 持久化模型调用账本；严格遵循 port 的最小契约，幂等由两个 unique 键兜底。 */
@Repository
public class MysqlModelUsageRecordStore implements ModelUsageRecordStore {

  private final ModelUsageRecordMapper mapper;

  public MysqlModelUsageRecordStore(ModelUsageRecordMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public int insert(ModelUsageRecord record) {
    Objects.requireNonNull(record, "record");
    int affected = mapper.insert(toDO(record));
    if (affected != 1) {
      throw new ConcurrentModificationException(
          "model_usage_record insert affected " + affected + " rows but expected 1");
    }
    return affected;
  }

  @Override
  public Optional<ModelUsageRecord> findByAssistantEntryId(long assistantEntryId) {
    ModelUsageRecordDO row = mapper.findByAssistantEntryId(assistantEntryId);
    return Optional.ofNullable(row).map(this::toRecord);
  }

  @Override
  public List<ModelUsageRecord> listByRunId(long runId) {
    return mapper.listByRunId(runId).stream().map(this::toRecord).toList();
  }

  @Override
  public List<ModelUsageRecord> listBySessionId(long sessionId) {
    return mapper.listBySessionId(sessionId).stream().map(this::toRecord).toList();
  }

  @Override
  public List<ModelUsageRecord> listByModelResourceId(long modelResourceId) {
    return mapper.listByModelResourceId(modelResourceId).stream().map(this::toRecord).toList();
  }

  static ModelUsageRecordDO toDO(ModelUsageRecord record) {
    ModelUsageDraft draft = record.draft();
    ModelUsage usage = draft.usage();
    ModelCost cost = draft.cost();
    ModelPricing pricing = draft.pricing();

    ModelUsageRecordDO target = new ModelUsageRecordDO();
    target.setId(record.id());
    target.setSessionId(record.sessionId());
    target.setRunId(record.runId());
    target.setAssistantEntryId(record.assistantEntryId());
    target.setAttempt(record.attempt());
    target.setTurnIndex(record.turnIndex());
    target.setProviderResourceId(draft.providerResourceId());
    target.setModelResourceId(draft.modelResourceId());
    target.setProviderType(draft.providerType().name());
    target.setProviderModelId(draft.providerModelId());
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
    target.setCostCurrency(cost.currency());
    target.setCostInput(cost.input());
    target.setCostOutput(cost.output());
    target.setCostCacheRead(cost.cacheRead());
    target.setCostCacheWrite(cost.cacheWrite());
    target.setCostCacheWriteLong(cost.cacheWriteLong());
    target.setCostReasoning(cost.reasoning());
    target.setCostTotal(cost.total());
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
    ModelCost cost =
        new ModelCost(
            source.getCostCurrency(),
            source.getCostInput(),
            source.getCostOutput(),
            source.getCostCacheRead(),
            source.getCostCacheWrite(),
            source.getCostCacheWriteLong(),
            source.getCostReasoning(),
            source.getCostTotal());
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
    ModelUsageDraft draft =
        new ModelUsageDraft(
            source.getProviderResourceId(),
            source.getModelResourceId(),
            ProviderType.valueOf(source.getProviderType()),
            source.getProviderModelId(),
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
        source.getRunId(),
        source.getAssistantEntryId(),
        source.getAttempt(),
        source.getTurnIndex(),
        draft,
        instant(source.getCreateTime()));
  }

  private static LocalDateTime utc(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
