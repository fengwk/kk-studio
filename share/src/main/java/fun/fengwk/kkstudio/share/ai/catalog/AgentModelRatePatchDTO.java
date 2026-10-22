package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 规则补丁；null/缺失字段表示不修改，显式 reasoning:null 表示回到继承输出。
 *
 * <p>写出由 {@link AgentModelRatePatchDTOSerializer} 负责，只输出实际声明的字段；属性上的 decimal 字符串声明描述同一 wire 形态。
 */
@Data
@JsonSerialize(using = AgentModelRatePatchDTOSerializer.class)
public class AgentModelRatePatchDTO {

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal input;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal output;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal cacheRead;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal cacheWrite;

  @JsonSerialize(contentUsing = DecimalStringSerializer.class)
  @JsonDeserialize(contentUsing = DecimalStringDeserializer.class)
  private @Nullable Map<String, BigDecimal> cacheWriteByTtl;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal reasoning;

  private boolean reasoningSpecified;

  /** 显式出现的 reasoning 字段（包括 null）才改变继承关系；普通缺省保持原值。 */
  @JsonSetter("reasoning")
  public void setReasoning(@Nullable BigDecimal reasoning) {
    this.reasoning = reasoning;
    this.reasoningSpecified = true;
  }

  @JsonIgnore
  public boolean isReasoningSpecified() {
    return reasoningSpecified;
  }
}
