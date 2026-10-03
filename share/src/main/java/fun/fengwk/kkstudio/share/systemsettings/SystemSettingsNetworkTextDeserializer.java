package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/** network wire 字段只接受 JSON 字符串，局部禁用标量转换；null/必填语义由领域边界校验。 */
public final class SystemSettingsNetworkTextDeserializer extends JsonDeserializer<String> {

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw JsonMappingException.from(parser, "network text field must be a JSON string");
    }
    return parser.getText();
  }
}
