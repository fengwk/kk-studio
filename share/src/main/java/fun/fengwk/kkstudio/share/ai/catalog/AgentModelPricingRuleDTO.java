package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/** 条件价格：时段与上下文长度门槛至少一项，全部满足时用本规则的分项单价（每百万 token）替换基础价格。 */
@Data
public class AgentModelPricingRuleDTO {

  /** 可空：生效时段。 */
  private @Nullable AgentModelPricingWindowDTO timeWindow;

  /** 可空正整数：请求提示 token（输入加缓存读写）达到该值时生效。 */
  private @Nullable Long minContextTokens;

  private BigDecimal inputPerMillionTokens;

  private BigDecimal outputPerMillionTokens;

  private BigDecimal cacheReadPerMillionTokens;

  private BigDecimal cacheWritePerMillionTokens;

  private BigDecimal cacheWriteLongPerMillionTokens;

  private BigDecimal reasoningPerMillionTokens;
}
