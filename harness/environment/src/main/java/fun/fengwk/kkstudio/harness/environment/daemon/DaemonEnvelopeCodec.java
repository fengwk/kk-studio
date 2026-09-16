package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Iterator;
import java.util.Set;

/**
 * Daemon envelope 的 JSON codec。
 *
 * <p>codec 在边界拒绝未知版本、未知消息类型、缺失字段、duplicate field、trailing token 和非对象 payload，避免将不完整 wire
 * 消息传给运行时；wire 字段 {@code environmentId} 必须是 canonical UUID 文本。HELLO 的 scope 必须为 null， 其余消息（除握手前的
 * ERROR）scope 必须非空。envelope 不含全局序号或 ACK，因此 codec 不做任何跨消息顺序校验。
 */
public final class DaemonEnvelopeCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("protocolVersion", "messageType", "environmentId", "invocationId", "payload");

  /** 将 envelope 编码为协议规定的 JSON 字段；environmentId 编码为 canonical UUID 文本（null 省略）。 */
  public String encode(DaemonEnvelope envelope) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("protocolVersion", envelope.protocolVersion());
    root.put("messageType", envelope.messageType().name());
    if (envelope.environmentId() != null) {
      root.put("environmentId", envelope.environmentId().toString());
    }
    if (envelope.invocationId() != null) {
      root.put("invocationId", envelope.invocationId());
    }
    root.set("payload", readPayload(envelope.payloadJson()));
    try {
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon envelope", error);
    }
  }

  /** 解码且校验当前协议版本的 envelope。 */
  public DaemonEnvelope decode(String json) {
    JsonNode root = readObject(json, "envelope");
    rejectUnknownFields(root);
    int protocolVersion = requiredInt(root, "protocolVersion");
    if (protocolVersion != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException("unsupported protocolVersion: " + protocolVersion);
    }
    String messageTypeValue = requiredText(root, "messageType");
    DaemonMessageType messageType;
    try {
      messageType = DaemonMessageType.valueOf(messageTypeValue);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("unknown daemon messageType: " + messageTypeValue, error);
    }
    JsonNode payload = root.get("payload");
    if (payload == null || !payload.isObject()) {
      throw new DaemonProtocolException("payload must be a JSON object");
    }
    String invocationId = optionalText(root, "invocationId");
    EnvironmentId environmentId = decodeScope(root, messageType);
    try {
      return new DaemonEnvelope(
          protocolVersion, messageType, environmentId, invocationId, writeJson(payload));
    } catch (RuntimeException error) {
      throw new DaemonProtocolException("envelope fields are invalid", error);
    }
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

  /** scope 契约：HELLO 必须 null，非调用消息按 envelope 自身 nullability 解码（record 校验 final）。 */
  private static EnvironmentId decodeScope(JsonNode root, DaemonMessageType messageType) {
    JsonNode value = root.get("environmentId");
    if (messageType == DaemonMessageType.HELLO) {
      if (value != null && !value.isNull()) {
        throw new DaemonProtocolException("HELLO envelope must not declare environmentId");
      }
      return null;
    }
    if (value == null || value.isNull()) {
      // WELCOME/READY/HEARTBEAT/调用消息必须携带 scope；握手前 ERROR 可空，由 record 校验。
      return null;
    }
    if (!value.isTextual()) {
      throw new DaemonProtocolException("environmentId must be a canonical UUID string");
    }
    try {
      return EnvironmentId.parse(value.textValue());
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "environmentId must be a canonical UUID string: " + value.textValue(), error);
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
