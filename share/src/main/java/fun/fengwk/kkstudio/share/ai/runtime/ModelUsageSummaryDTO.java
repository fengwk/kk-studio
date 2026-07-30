package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Data
public class ModelUsageSummaryDTO {
  private String scopeType;
  private String scopeId;
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
  private BigDecimal cacheHitRatio;
  private BigDecimal tokenReadRatio;
  private long unamortizedCacheWriteTokens;
  private List<ModelUsageCostSummaryDTO> costs = List.of();

  public void setCosts(List<ModelUsageCostSummaryDTO> costs) {
    this.costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
  }
}
