package fun.fengwk.kkstudio.share.ai.catalog;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.math.BigDecimal;

/** 价格 decimal 始终写成普通十进制字符串，避免科学计数法改变 wire 文本形态。 */
public final class DecimalStringSerializer extends ValueSerializer<BigDecimal> {

  @Override
  public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
    generator.writeString(value.toPlainString());
  }
}
