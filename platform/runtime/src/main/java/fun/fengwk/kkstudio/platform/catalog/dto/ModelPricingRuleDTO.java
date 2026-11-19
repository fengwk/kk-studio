package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 固定三种定价规则共用的 wire 对象；每种 type 只允许使用其契约字段。 */
@Data
@JsonSerialize(using = ModelPricingRuleDTOSerializer.class)
public class ModelPricingRuleDTO {

  private String type;

  private @Nullable Integer minInputTokens;

  private @Nullable Integer maxInputTokensExclusive;

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

  private boolean cacheWriteByTtlSpecified;

  private boolean reasoningSpecified;

  private @Nullable String start;

  private @Nullable String end;

  private @Nullable String timezone;

  private @Nullable List<Integer> weekdays;

  private @Nullable String serviceTier;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal factor;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal inputFactor;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal outputFactor;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal cacheReadFactor;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal cacheWriteFactor;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal reasoningFactor;

  @JsonSetter("cacheWriteByTtl")
  public void setCacheWriteByTtl(@Nullable Map<String, BigDecimal> cacheWriteByTtl) {
    this.cacheWriteByTtl = cacheWriteByTtl;
    this.cacheWriteByTtlSpecified = true;
  }

  @JsonIgnore
  public boolean isCacheWriteByTtlSpecified() {
    return cacheWriteByTtlSpecified;
  }

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
