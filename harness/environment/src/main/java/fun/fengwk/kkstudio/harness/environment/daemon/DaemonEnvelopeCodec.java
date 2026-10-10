package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon envelope 的 JSON codec。
 *
 * <p>codec 在边界拒绝未知版本、未知消息类型、缺失字段、duplicate field、trailing token 和非对象 payload，避免将不完整 wire
 * 消息传给运行时；wire 字段 {@code environmentId} 必须是 canonical UUID 文本。HELLO 的 scope 必须为 null， 其余消息（除握手前的
 * ERROR）scope 必须非空。envelope 不含全局序号或 ACK，因此 codec 不做任何跨消息顺序校验。
 *
 * <p>整包 encode/decode 共用同一硬 UTF-8 上限 {@link #MAX_ENVELOPE_UTF8_BYTES}（与共享 carrier 的单条逻辑消息预算一致）， 没有
 * raw 旁路：任何类型都先量出整包大小再决定序列化/解析，超限即拒绝。{@link #payloadBudget} 精确扣除动态外壳开销。
 */
public final class DaemonEnvelopeCodec {

  /** 逻辑 envelope 的硬 UTF-8 字节上限：与共享 carrier 的单条逻辑消息预算同源（8 MiB）。 */
  public static final int MAX_ENVELOPE_UTF8_BYTES = NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES;

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper(
          JsonFactory.builder()
              // 解析放大防护：字符串、嵌套、数字与文档长度全部有界在逻辑 envelope 上限内。
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxStringLength(MAX_ENVELOPE_UTF8_BYTES)
                      .maxNestingDepth(100)
                      .maxNumberLength(1000)
                      .maxDocumentLength(MAX_ENVELOPE_UTF8_BYTES)
                      .build())
              .build());

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("protocolVersion", "messageType", "environmentId", "invocationId", "payload");

  /**
   * payload 预算纯计数用的零 UUID scope 占位：canonical UUID 文本恒为 36 个 ASCII 字符，与真实 scope 值无关。 永不发送、不代表任何业务身份。
   */
  private static final EnvironmentId COUNTING_ENVIRONMENT_ID =
      EnvironmentId.parse("00000000-0000-0000-0000-000000000000");

  /** 将 envelope 编码为协议规定的 JSON 字段；environmentId 编码为 canonical UUID 文本（null 省略）。 */
  public String encode(DaemonEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    return writeBoundedEnvelope(buildEnvelopeTree(envelope));
  }

  /**
   * 该类型/invocationId 可用的 payload UTF-8 预算，使整包 envelope 不超过 {@link #MAX_ENVELOPE_UTF8_BYTES}。
   *
   * <p>用空 payload（{@code {}}）的外壳实际编码长度扣除动态开销：environmentId 用零 UUID 占位（宽度恒定），invocationId
   * 用真实值以计入引号/反斜杠/多字节转义，无固定 reserve，也不读取可变全局 scope。外壳本身超限时直接抛固定协议错误。
   */
  public int payloadBudget(DaemonMessageType messageType, String invocationId) {
    requireResultType(messageType);
    ObjectNode emptyPayload = OBJECT_MAPPER.createObjectNode();
    ObjectNode shell =
        buildEnvelopeTree(messageType, COUNTING_ENVIRONMENT_ID, invocationId, emptyPayload);
    int envelopeBytes = ResourceRef.utf8Length(writeBoundedEnvelope(shell), "envelope");
    return MAX_ENVELOPE_UTF8_BYTES - envelopeBytes + 2;
  }

  /** 该 payload 连同动态外壳是否能整包放进硬上限；用于运行时按真实 invocationId 决定是否注入文本。 */
  public boolean payloadFits(
      DaemonMessageType messageType, String invocationId, ObjectNode payload) {
    requireResultType(messageType);
    Objects.requireNonNull(payload, "payload");
    return BoundedJsonWriter.fits(
        buildEnvelopeTree(messageType, COUNTING_ENVIRONMENT_ID, invocationId, payload),
        MAX_ENVELOPE_UTF8_BYTES);
  }

  private static void requireResultType(DaemonMessageType messageType) {
    switch (Objects.requireNonNull(messageType, "messageType")) {
      case PROGRESS, COMPLETED, FAILED, CANCELLED -> {}
      default -> throw new IllegalArgumentException("messageType must be a capability result");
    }
  }

  /** 解码且校验当前协议版本的 envelope。 */
  public DaemonEnvelope decode(String json) {
    Objects.requireNonNull(json, "json");
    // 解析前施加 UTF-8 上限，超限正文不进入 Jackson。
    try {
      if (ResourceRef.utf8LengthUpTo(json, "envelope", MAX_ENVELOPE_UTF8_BYTES)
          > MAX_ENVELOPE_UTF8_BYTES) {
        throw new DaemonProtocolException(
            "daemon envelope exceeds " + MAX_ENVELOPE_UTF8_BYTES + " UTF-8 bytes");
      }
    } catch (DaemonProtocolException error) {
      throw error;
    } catch (IllegalArgumentException invalidUnicode) {
      throw new DaemonProtocolException("daemon envelope must be valid Unicode");
    }
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
      // 固定去敏：不回显原字符串，也不保留 valueOf 的 cause。
      throw new DaemonProtocolException("unknown daemon messageType");
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
      throw new DaemonProtocolException("envelope fields are invalid");
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
      // 固定去敏：绝不复用 Jackson 消息（可能内联被拒 body 片段），也不保留其 cause。
      throw new DaemonProtocolException("json must be valid JSON");
    }
  }

  /** 将 JSON 节点序列化为 payload 文本。 */
  public String writeJson(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon payload");
    }
  }

  /** 按协议字段顺序构建整包 JSON 树；payload 解析为 object 后原样嵌入。 */
  private ObjectNode buildEnvelopeTree(DaemonEnvelope envelope) {
    return buildEnvelopeTree(
        envelope.messageType(),
        envelope.environmentId(),
        envelope.invocationId(),
        readPayload(envelope.payloadJson()));
  }

  private ObjectNode buildEnvelopeTree(
      DaemonMessageType messageType,
      EnvironmentId environmentId,
      String invocationId,
      JsonNode payload) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("protocolVersion", DaemonProtocol.VERSION);
    root.put("messageType", messageType.name());
    if (environmentId != null) {
      root.put("environmentId", environmentId.toString());
    }
    if (invocationId != null) {
      root.put("invocationId", invocationId);
    }
    root.set("payload", payload);
    return root;
  }

  /** 整包固定上限：超过 {@link #MAX_ENVELOPE_UTF8_BYTES} 即在物化前中止并抛固定协议错误。 */
  private String writeBoundedEnvelope(ObjectNode root) {
    String json = BoundedJsonWriter.write(root, MAX_ENVELOPE_UTF8_BYTES);
    if (json == null) {
      throw new DaemonProtocolException(
          "daemon envelope exceeds " + MAX_ENVELOPE_UTF8_BYTES + " UTF-8 bytes");
    }
    return json;
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
      // 固定去敏：不回显被拒的文本值。
      throw new DaemonProtocolException("environmentId must be a canonical UUID string");
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
      // 固定去敏：不复用可能内联被拒 body 的 Jackson 消息，也不保留其 cause。
      throw new DaemonProtocolException(fieldName + " must be valid JSON");
    }
  }

  private void rejectUnknownFields(JsonNode root) {
    Iterator<String> fields = root.fieldNames();
    while (fields.hasNext()) {
      // 固定去敏：不回显未知字段名。
      if (!ENVELOPE_FIELDS.contains(fields.next())) {
        throw new DaemonProtocolException("envelope has an unsupported field");
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
