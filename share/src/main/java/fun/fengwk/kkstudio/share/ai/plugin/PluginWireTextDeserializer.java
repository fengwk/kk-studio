package fun.fengwk.kkstudio.share.ai.plugin;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Plugin 管理面 wire 文本字段只接受 JSON 字符串：数字、布尔或 null 绝不被强制转换成字符串。
 *
 * <p>与系统设置的 {@code SystemSettingsNetworkTextDeserializer} 同一形态的局部严格化：只作用于显式标注的属性，绝不改全局 mapper。
 * 失败消息只说明期望的 token 形态，绝不回显字段内容。
 */
public final class PluginWireTextDeserializer extends ValueDeserializer<String> {

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw DatabindException.from(parser, "plugin wire text field must be a JSON string");
    }
    return parser.getString();
  }
}
