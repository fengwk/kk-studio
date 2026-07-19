package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;

/**
 * Daemon v1 envelope 的 JSON codec。
 *
 * <p>codec 在边界拒绝未知版本、未知消息类型、缺失字段和非对象 payload，避免将不完整 wire 消息传给运行时。
 */
public final class DaemonEnvelopeCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "protocolVersion",
          "messageType",
          "environmentName",
          "invocationId",
          "sequence",
          "payload");

  /** 将 envelope 编码为协议规定的 JSON 字段。 */
  public String encode(DaemonEnvelope envelope) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("protocolVersion", envelope.protocolVersion());
    root.put("messageType", envelope.messageType().name());
    root.put("environmentName", envelope.environmentName());
    if (envelope.invocationId() != null) {
      root.put("invocationId", envelope.invocationId());
    }
    root.put("sequence", envelope.sequence());
    root.set("payload", readPayload(envelope.payloadJson()));
    try {
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon envelope", error);
    }
  }

  /** 解码且校验单个 v1 envelope。 */
  public DaemonEnvelope decode(String json) {
    JsonNode root = readObject(json, "envelope");
    rejectUnknownFields(root);
    int protocolVersion = requiredInt(root, "protocolVersion");
    if (protocolVersion != DaemonProtocol.VERSION_1) {
      throw new DaemonProtocolException("unsupported protocolVersion: " + protocolVersion);
    }
    String messageTypeValue = requiredText(root, "messageType");
    DaemonMessageType messageType;
    try {
      messageType = DaemonMessageType.valueOf(messageTypeValue);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("unknown daemon messageType: " + messageTypeValue, error);
    }
    long sequence = requiredLong(root, "sequence");
    if (sequence < 0) {
      throw new DaemonProtocolException("sequence must not be negative");
    }
    JsonNode payload = root.get("payload");
    if (payload == null || !payload.isObject()) {
      throw new DaemonProtocolException("payload must be a JSON object");
    }
    String invocationId = optionalText(root, "invocationId");
    return new DaemonEnvelope(
        protocolVersion,
        messageType,
        requiredText(root, "environmentName"),
        invocationId,
        sequence,
        writeJson(payload));
  }

  /** 创建一个空的 JSON object payload。 */
  public ObjectNode createPayload() {
    return OBJECT_MAPPER.createObjectNode();
  }

  /** 读取 envelope payload，供消息处理器按消息类型解析。 */
  public ObjectNode readPayload(DaemonEnvelope envelope) {
    return readPayload(envelope.payloadJson());
  }

  /** 读取任意有效 JSON 值，供嵌套 wire 字段编码。 */
  public JsonNode readJson(String json) {
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null) {
        throw new DaemonProtocolException("json must not be null");
      }
      return value;
    } catch (JsonProcessingException | IllegalArgumentException error) {
      throw new DaemonProtocolException("json must be valid JSON", error);
    }
  }

  /** 将 JSON 节点序列化为 payload 文本。 */
  public String writeJson(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon payload", error);
    }
  }

  private ObjectNode readPayload(String json) {
    return (ObjectNode) readObject(json, "payload");
  }

  private JsonNode readObject(String json, String fieldName) {
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException(fieldName + " must be a JSON object");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException(fieldName + " must be valid JSON", error);
    }
  }

  private void rejectUnknownFields(JsonNode root) {
    Iterator<String> fields = root.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!ENVELOPE_FIELDS.contains(field)) {
        throw new DaemonProtocolException("envelope has unknown field: " + field);
      }
    }
  }

  private int requiredInt(JsonNode root, String fieldName) {
    JsonNode value = root.get(fieldName);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new DaemonProtocolException(fieldName + " must be an integer");
    }
    return value.intValue();
  }

  private long requiredLong(JsonNode root, String fieldName) {
    JsonNode value = root.get(fieldName);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(fieldName + " must be a long integer");
    }
    return value.longValue();
  }

  private String requiredText(JsonNode root, String fieldName) {
    String value = optionalText(root, fieldName);
    if (value == null || value.isBlank()) {
      throw new DaemonProtocolException(fieldName + " must be a non-blank string");
    }
    return value;
  }

  private String optionalText(JsonNode root, String fieldName) {
    JsonNode value = root.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new DaemonProtocolException(fieldName + " must be a string");
    }
    return value.textValue();
  }
}
