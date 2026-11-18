package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.List;

/** 固定三种定价规则共用的 wire 对象；每种 type 只允许使用其契约字段。 */
@Data
public class ModelPricingRuleDTO {

  private String type;

  private Integer minInputTokens;

  private Integer maxInputTokensExclusive;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal input;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal output;

  private String start;

  private String end;

  private String timezone;

  private List<Integer> weekdays;

  private String serviceTier;

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
}
