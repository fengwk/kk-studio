package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ModelUsageCostSummaryDTO {
  private String currency;
  private BigDecimal input;
  private BigDecimal output;
  private BigDecimal cacheRead;
  private BigDecimal cacheWrite;
  private BigDecimal cacheWriteLong;
  private BigDecimal reasoning;
  private BigDecimal total;
}
