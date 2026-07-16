package fun.fengwk.kkstudio.share.model;

import java.math.BigDecimal;
import lombok.Data;

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
