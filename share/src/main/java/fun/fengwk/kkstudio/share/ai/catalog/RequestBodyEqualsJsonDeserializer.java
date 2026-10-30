package fun.fengwk.kkstudio.share.ai.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * requestBody.equals 的 wire 标量反序列化器：数字直接保留 {@link JsonNode} 的十进制/整数原文精度，不接受隐式 float 转换，也不接受
 * object/array 等非标量 token。
 *
 * <p>该字段的显式 JSON null 必须保留为 {@link tools.jackson.databind.node.NullNode}，与字段缺省时 Java null 的语义区分。
 */
public final class RequestBodyEqualsJsonDeserializer extends ValueDeserializer<JsonNode> {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  @Override
  public JsonNode getNullValue(DeserializationContext context) {
    return NODES.nullNode();
  }

  @Override
  public JsonNode deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    return switch (parser.currentToken()) {
      case VALUE_STRING -> NODES.textNode(parser.getString());
      case VALUE_NUMBER_INT -> NODES.numberNode(parser.getBigIntegerValue());
      case VALUE_NUMBER_FLOAT -> NODES.numberNode(parser.getDecimalValue());
      case VALUE_TRUE, VALUE_FALSE -> NODES.booleanNode(parser.getBooleanValue());
      case VALUE_NULL -> NODES.nullNode();
      default -> throw DatabindException.from(
          parser, "requestBody.equals must be a JSON string, number, boolean or null");
    };
  }
}
