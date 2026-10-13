package fun.fengwk.kkstudio.share.systemsettings;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** network wire 字段只接受 JSON 字符串，局部禁用标量转换；null/必填语义由领域边界校验。 */
public final class SystemSettingsNetworkTextDeserializer extends ValueDeserializer<String> {

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw DatabindException.from(parser, "network text field must be a JSON string");
    }
    return parser.getString();
  }
}
