package fun.fengwk.kkstudio.share.ai.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;

/** 价格 wire 字段只接受十进制 JSON 字符串，绝不接受 JSON 数值或浮点近似。 */
public final class DecimalStringDeserializer extends ValueDeserializer<BigDecimal> {

  @Override
  public BigDecimal deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw DatabindException.from(parser, "price must be a decimal JSON string");
    }
    String text = parser.getString();
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException error) {
      throw DatabindException.from(parser, "price must be a decimal JSON string");
    }
  }
}
