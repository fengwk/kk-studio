package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.Map;

/** 基础费率；缺省普通价格归一化为 0，TTL 分价存在时普通缓存写价可空；reasoning 为 null 表示继承输出价。 */
@Data
public class ModelRateSetDTO {

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal input;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal output;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private BigDecimal cacheRead;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal cacheWrite;

  /** key 为正整数秒，value 为该 TTL 的每百万 token 写价。 */
  @JsonSerialize(contentUsing = DecimalStringSerializer.class)
  @JsonDeserialize(contentUsing = DecimalStringDeserializer.class)
  private Map<String, BigDecimal> cacheWriteByTtl;

  @JsonSerialize(using = DecimalStringSerializer.class)
  @JsonDeserialize(using = DecimalStringDeserializer.class)
  private @Nullable BigDecimal reasoning;
}
