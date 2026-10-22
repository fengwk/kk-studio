package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.Map;

/** 规则补丁；null/缺失字段表示不修改，显式 reasoning:null 表示回到继承输出。 */
@Data
public class AgentModelRatePatchDTO {

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

  @JsonSerialize(contentUsing = ToStringSerializer.class)
  @JsonDeserialize(contentUsing = DecimalStringDeserializer.class)
  private Map<String, BigDecimal> cacheWriteByTtl;

  @JsonSerialize(using = ToStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal reasoning;

  private boolean reasoningSpecified;

  /** 显式出现的 reasoning 字段（包括 null）才改变继承关系；普通缺省保持原值。 */
  @JsonSetter("reasoning")
  public void setReasoning(BigDecimal reasoning) {
    this.reasoning = reasoning;
    this.reasoningSpecified = true;
  }

  @JsonIgnore
  public boolean isReasoningSpecified() {
    return reasoningSpecified;
  }
}
