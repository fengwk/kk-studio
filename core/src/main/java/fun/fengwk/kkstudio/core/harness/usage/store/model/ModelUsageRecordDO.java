package fun.fengwk.kkstudio.core.harness.usage.store.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** {@code model_usage_record} 行映射：一次 Provider 调用的不可变用量/成本账本（按 Assistant Entry 唯一）。 */
@Data
public class ModelUsageRecordDO {
  /** 账本主键（独立 Snowflake namespace）。 */
  private Long id;

  /** 所属 Session。 */
  private Long sessionId;

  /** 所属 Run。 */
  private Long runId;

  /** 产生账本的 Assistant Entry（唯一）。 */
  private Long assistantEntryId;

  /** 写入时的 Run attempt。 */
  private Integer attempt;

  /** 写入时的 Run turnIndex。 */
  private Integer turnIndex;

  /** 冻结的 provider 资源 id。 */
  private Long providerResourceId;

  /** 冻结的 model 资源 id。 */
  private Long modelResourceId;

  /** 冻结的 Provider 类型。 */
  private String providerType;

  /** 冻结的 Provider 模型 id。 */
  private String providerModelId;

  /** 冻结的 PromptCacheMode。 */
  private String promptCacheMode;

  /** 冻结的 PromptCacheRetention。 */
  private String promptCacheRetention;

  /** 是否缓存可计费。 */
  private Boolean cacheEligible;

  /** 缓存亲和 key；NONE 时为空。 */
  private String cacheAffinityKey;

  /** 冻结的 ProviderStopReason。 */
  private String stopReason;

  /** input tokens。 */
  private Long usageInputTokens;

  /** output tokens。 */
  private Long usageOutputTokens;

  /** cache read tokens。 */
  private Long usageCacheReadTokens;

  /** cache write tokens。 */
  private Long usageCacheWriteTokens;

  /** cache write long tokens。 */
  private Long usageCacheWriteLongTokens;

  /** reasoning tokens。 */
  private Long usageReasoningTokens;

  /** Provider 报告的 total tokens。 */
  private Long usageProviderTotalTokens;

  /** 成本币种。 */
  private String costCurrency;

  /** input 成本。 */
  private BigDecimal costInput;

  /** output 成本。 */
  private BigDecimal costOutput;

  /** cache read 成本。 */
  private BigDecimal costCacheRead;

  /** cache write 成本。 */
  private BigDecimal costCacheWrite;

  /** cache write long 成本。 */
  private BigDecimal costCacheWriteLong;

  /** reasoning 成本。 */
  private BigDecimal costReasoning;

  /** 六分项成本之和。 */
  private BigDecimal costTotal;

  /** pricing 币种。 */
  private String pricingCurrency;

  /** 冻结的 pricing tier。 */
  private String pricingTier;

  /** 冻结的 service tier。 */
  private String pricingServiceTier;

  /** service tier 乘数。 */
  private BigDecimal pricingServiceTierMultiplier;

  /** 冻结的 pricing version。 */
  private String pricingVersion;

  /** input 单价（每百万 tokens）。 */
  private BigDecimal pricingInputPerMillionTokens;

  /** output 单价（每百万 tokens）。 */
  private BigDecimal pricingOutputPerMillionTokens;

  /** cache read 单价（每百万 tokens）。 */
  private BigDecimal pricingCacheReadPerMillionTokens;

  /** cache write 单价（每百万 tokens）。 */
  private BigDecimal pricingCacheWritePerMillionTokens;

  /** cache write long 单价（每百万 tokens）。 */
  private BigDecimal pricingCacheWriteLongPerMillionTokens;

  /** reasoning 单价（每百万 tokens）。 */
  private BigDecimal pricingReasoningPerMillionTokens;

  /** Provider 报告的 request id。 */
  private String requestId;

  /** Provider 报告的 service tier。 */
  private String reportedServiceTier;

  /** Provider 原始 usage JSON（不含正文）。 */
  private String rawUsageJson;

  /** 入库时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
