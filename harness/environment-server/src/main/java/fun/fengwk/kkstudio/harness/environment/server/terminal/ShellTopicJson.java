package fun.fengwk.kkstudio.harness.environment.server.terminal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * shell 固定 topic 共用的严格 JSON 工具：duplicate/trailing/unknown 拒绝与 canonical UUID。
 *
 * <p>只服务 {@link TerminalDispatchCodec} 与 {@link TerminalDeliveryCodec}；不对外暴露，也不定义第二套 payload
 * 模型。所有解码/编码失败都转为固定去敏的 {@link IllegalArgumentException}，不保留原始 Jackson/UUID 异常，避免把非法值或原 payload 通过异常
 * cause 回显出去。
 */
final class ShellTopicJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private ShellTopicJson() {}

  static ObjectNode newObject() {
    return MAPPER.createObjectNode();
  }

  static ObjectNode parse(String text, String context) {
    JsonNode value;
    try {
      value = MAPPER.readTree(text);
    } catch (JsonProcessingException error) {
      // 不保留 cause：Jackson 异常会回显非法值/原 payload。
      throw new IllegalArgumentException(context + " must be valid JSON");
    }
    return requireObject(value, context);
  }

  static ObjectNode requireObject(JsonNode value, String path) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(path + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  static void requireExactFields(ObjectNode root, Set<String> fields, String context) {
    Iterator<String> names = root.fieldNames();
    int count = 0;
    while (names.hasNext()) {
      String name = names.next();
      count++;
      if (!fields.contains(name)) {
        throw new IllegalArgumentException(context + " has unknown field");
      }
    }
    if (count != fields.size()) {
      throw new IllegalArgumentException(context + " is missing fields");
    }
  }

  static UUID canonicalUuid(ObjectNode root, String field, String context) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be a string");
    }
    String text = value.textValue();
    UUID parsed;
    try {
      parsed = UUID.fromString(text);
    } catch (IllegalArgumentException error) {
      // 不保留 cause：UUID 异常会回显非法值。
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical UUID string");
    }
    if (!parsed.toString().equals(text)) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical UUID string");
    }
    return parsed;
  }

  static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode shell topic payload");
    }
  }
}
