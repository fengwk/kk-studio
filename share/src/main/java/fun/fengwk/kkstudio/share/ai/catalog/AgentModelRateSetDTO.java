package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.Map;

/** 基础费率；未配置的普通价格归一化为 0，reasoning 为 null 表示继承输出价。 */
@Data
public class AgentModelRateSetDTO {

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

  /** key 为正整数秒，value 为该 TTL 的每百万 token 写价。 */
  @JsonSerialize(contentUsing = ToStringSerializer.class)
  @JsonDeserialize(contentUsing = DecimalStringDeserializer.class)
  private Map<String, BigDecimal> cacheWriteByTtl;

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal reasoning;
}
