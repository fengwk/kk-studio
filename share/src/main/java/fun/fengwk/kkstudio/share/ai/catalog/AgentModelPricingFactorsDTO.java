package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/** 价格字段倍率；null 表示该字段不应用倍率（等价于 1）。 */
@Data
public class AgentModelPricingFactorsDTO {

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal input;

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal output;

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal cacheRead;

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal cacheWrite;

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal reasoning;
}
