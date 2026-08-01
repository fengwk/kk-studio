package fun.fengwk.kkstudio.core.ai.runtime.usage.store.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** {@code harness_model_usage} 行映射：一次 Assistant Entry 的模型用量账本。 */
@Data
public class ModelUsageRecordDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Session。 */
  private Long sessionId;

  /** 所属 Thread。 */
  private Long threadId;

  /** 产生账本的 Assistant Entry。 */
  private Long assistantEntryId;

  /** 冻结的 Provider 名称。 */
  private String providerName;

  /** 冻结的 Model 名称。 */
  private String modelName;

  /** 冻结的 Provider 类型。 */
  private String providerType;

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

  /** Provider 报告总 tokens。 */
  private Long usageProviderTotalTokens;

  /** 计价快照币种。 */
  private String pricingCurrency;

  /** 计价层级。 */
  private String pricingTier;

  /** 计价服务层级。 */
  private String pricingServiceTier;

  /** 服务层级价格乘数。 */
  private BigDecimal pricingServiceTierMultiplier;

  /** 计价配置版本。 */
  private String pricingVersion;

  /** 每百万输入 token 单价。 */
  private BigDecimal pricingInputPerMillionTokens;

  /** 每百万输出 token 单价。 */
  private BigDecimal pricingOutputPerMillionTokens;

  /** 每百万缓存读取 token 单价。 */
  private BigDecimal pricingCacheReadPerMillionTokens;

  /** 每百万短期缓存写入 token 单价。 */
  private BigDecimal pricingCacheWritePerMillionTokens;

  /** 每百万长期缓存写入 token 单价。 */
  private BigDecimal pricingCacheWriteLongPerMillionTokens;

  /** 每百万推理 token 单价。 */
  private BigDecimal pricingReasoningPerMillionTokens;

  /** Provider 返回的请求 id。 */
  private String requestId;

  /** Provider 实际报告的服务层级。 */
  private String reportedServiceTier;

  /** Provider 原始 usage JSON。 */
  private String rawUsageJson;

  /** 创建时间（映射 {@code created_at}）。 */
  private OffsetDateTime createTime;
}
