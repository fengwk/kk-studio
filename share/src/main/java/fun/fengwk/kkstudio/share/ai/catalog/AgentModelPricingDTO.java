package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.math.BigDecimal;

/** Pricing snapshot carried by an Agent model for deterministic per-call cost calculation. */
@Data
public class AgentModelPricingDTO {

  private String currency;
  private String pricingTier;
  private String serviceTier;
  private BigDecimal serviceTierMultiplier;
  private String version;
  private BigDecimal inputPerMillionTokens;
  private BigDecimal outputPerMillionTokens;
  private BigDecimal cacheReadPerMillionTokens;
  private BigDecimal cacheWritePerMillionTokens;
  private BigDecimal cacheWriteLongPerMillionTokens;
  private BigDecimal reasoningPerMillionTokens;
}
