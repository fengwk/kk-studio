package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.math.BigDecimal;

/** Agent 模型携带的定价快照，用于每次调用的确定性成本计算。 */
@Data
public class AgentModelPricingDTO {

  /** 必填非空白货币代码（惯例为 ISO 4217 代码，如 {@code USD}）。 */
  private String currency;

  /** 必填非空白定价档位标识（pricing tier）。 */
  private String pricingTier;

  /** 必填非空白服务档位标识（service tier）。 */
  private String serviceTier;

  /** 必填正数 BigDecimal：服务档位倍率。 */
  private BigDecimal serviceTierMultiplier;

  /** 必填非空白定价版本标识。 */
  private String version;

  /** 每百万输入 Token 价格（非负 BigDecimal）。 */
  private BigDecimal inputPerMillionTokens;

  /** 每百万输出 Token 价格（非负 BigDecimal）。 */
  private BigDecimal outputPerMillionTokens;

  /** 每百万缓存读取（命中）Token 价格（非负 BigDecimal）。 */
  private BigDecimal cacheReadPerMillionTokens;

  /** 每百万缓存写入 Token 价格（非负 BigDecimal）。 */
  private BigDecimal cacheWritePerMillionTokens;

  /** 每百万长期缓存写入 Token 价格（非负 BigDecimal）。 */
  private BigDecimal cacheWriteLongPerMillionTokens;

  /** 每百万 reasoning Token 价格（非负 BigDecimal）。 */
  private BigDecimal reasoningPerMillionTokens;
}
