package fun.fengwk.kkstudio.share.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * `protocolOptionsJson` 的 wire 形状检查：只拒绝非 JSON string token，内容原样交给调用方。
 *
 * <p>本类不解析选项内容。严格 object 语法、64 KiB UTF-8 上限与 canonical 化由 harness-model 的 {@code
 * ProviderProtocolOptions} 在配置解析时统一执行；share 不能反向依赖 harness-model，也不复制这些规则。
 */
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
