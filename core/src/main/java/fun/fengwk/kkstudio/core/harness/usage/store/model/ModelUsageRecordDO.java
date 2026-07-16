package fun.fengwk.kkstudio.core.harness.usage.store.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** model_usage_record 行映射；完整平铺一次 ModelUsageDraft 的所有字段。 */
@Data
public class ModelUsageRecordDO {
  private Long id;
  private Long sessionId;
  private Long runId;
  private Long assistantEntryId;
  private Integer attempt;
  private Integer turnIndex;
  private Long providerResourceId;
  private Long modelResourceId;
  private String providerType;
  private String providerModelId;
  private String promptCacheMode;
  private String promptCacheRetention;
  private Boolean cacheEligible;
  private String cacheAffinityKey;
  private String stopReason;
  private Long usageInputTokens;
  private Long usageOutputTokens;
  private Long usageCacheReadTokens;
  private Long usageCacheWriteTokens;
  private Long usageCacheWriteLongTokens;
  private Long usageReasoningTokens;
  private Long usageProviderTotalTokens;
  private String costCurrency;
  private BigDecimal costInput;
  private BigDecimal costOutput;
  private BigDecimal costCacheRead;
  private BigDecimal costCacheWrite;
  private BigDecimal costCacheWriteLong;
  private BigDecimal costReasoning;
  private BigDecimal costTotal;
  private String pricingCurrency;
  private String pricingTier;
  private String pricingServiceTier;
  private BigDecimal pricingServiceTierMultiplier;
  private String pricingVersion;
  private BigDecimal pricingInputPerMillionTokens;
  private BigDecimal pricingOutputPerMillionTokens;
  private BigDecimal pricingCacheReadPerMillionTokens;
  private BigDecimal pricingCacheWritePerMillionTokens;
  private BigDecimal pricingCacheWriteLongPerMillionTokens;
  private BigDecimal pricingReasoningPerMillionTokens;
  private String requestId;
  private String reportedServiceTier;
  private String rawUsageJson;
  private LocalDateTime createTime;
}
