package fun.fengwk.kkstudio.share.ai.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 局部禁用 Jackson 对字符串字段的标量强制转换，不影响 catalog 其他字段。 */
public final class ProtocolOptionsJsonStringDeserializer extends ValueDeserializer<String> {

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw DatabindException.from(parser, "protocolOptionsJson must be a JSON string");
    }
    return parser.getString();
  }
}
