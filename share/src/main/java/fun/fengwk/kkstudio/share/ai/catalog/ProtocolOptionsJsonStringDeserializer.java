package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/** 局部禁用 Jackson 对字符串字段的标量强制转换，不影响 catalog 其他字段。 */
public final class ProtocolOptionsJsonStringDeserializer extends JsonDeserializer<String> {

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw JsonMappingException.from(parser, "protocolOptionsJson must be a JSON string");
    }
    return parser.getText();
  }
}
