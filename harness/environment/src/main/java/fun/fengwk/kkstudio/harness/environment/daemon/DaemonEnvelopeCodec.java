package fun.fengwk.kkstudio.harness.environment.daemon;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Set;

/**
 * Daemon envelope 的 JSON codec。
 *
 * <p>codec 在边界拒绝未知版本、未知消息类型、缺失字段、duplicate field、trailing token 和非对象 payload，避免将不完整 wire
 * 消息传给运行时；wire 字段 {@code environmentId} 必须是 canonical UUID 文本。HELLO 的 scope 必须为 null， 其余消息（除握手前的
 * ERROR）scope 必须非空。envelope 不含全局序号或 ACK，因此 codec 不做任何跨消息顺序校验。
 */
public final class DaemonEnvelopeCodec {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          // JSON number 以 BigDecimal 保留树内精度，避免 1e309 -> "Infinity"、1e-324 -> 0.0 的 wire 失真。
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

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
    ObjectNode payload = readPayload(envelope.payloadJson());
    requireEmptyExitPayload(envelope.messageType(), payload);
    root.set("payload", payload);
    try {
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
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
    requireEmptyExitPayload(messageType, payload);
    String invocationId = optionalText(root, "invocationId");
    EnvironmentId environmentId = decodeScope(root, messageType);
    try {
      return new DaemonEnvelope(
          protocolVersion, messageType, environmentId, invocationId, writeJson(payload));
    } catch (RuntimeException error) {
      throw new DaemonProtocolException("envelope fields are invalid", error);
    }
  }

  /** 退出事实没有业务 payload；编码与解码必须在同一 wire 边界拒绝额外字段。 */
  private static void requireEmptyExitPayload(DaemonMessageType messageType, JsonNode payload) {
    if (messageType == DaemonMessageType.EXECUTION_EXITED && !payload.isEmpty()) {
      throw new DaemonProtocolException("EXECUTION_EXITED payload must be an empty object");
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
      if (value == null || value.isMissingNode()) {
        throw new DaemonProtocolException("json must not be null");
      }
      return value;
    } catch (JacksonException | IllegalArgumentException error) {
      throw new DaemonProtocolException("json must be valid JSON", error);
    }
  }

  /** 将 JSON 节点序列化为 payload 文本。 */
  public String writeJson(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
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
    if (!value.isString()) {
      throw new DaemonProtocolException("environmentId must be a canonical UUID string");
    }
    try {
      return EnvironmentId.parse(value.stringValue());
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "environmentId must be a canonical UUID string: " + value.stringValue(), error);
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
    } catch (JacksonException error) {
      throw new DaemonProtocolException(fieldName + " must be valid JSON", error);
    }
  }

  private void rejectUnknownFields(JsonNode root) {
    for (String field : root.propertyNames()) {
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
    if (!value.isString()) {
      throw new DaemonProtocolException(fieldName + " must be a string");
    }
    return value.stringValue();
  }
}
