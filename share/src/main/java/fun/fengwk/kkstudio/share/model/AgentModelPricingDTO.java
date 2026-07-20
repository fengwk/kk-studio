package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Pricing snapshot carried with every Agent model. The six per-million-token prices are preserved
 * as-is from the existing runtime semantics so existing billing behaviour is unchanged.
 */
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
