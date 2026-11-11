package fun.fengwk.kkstudio.harness.codec;

import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.requireFields;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.api.command.CommandValueValidation;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationKind;
import fun.fengwk.kkstudio.harness.model.HarnessJson;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Typed Thread command payload 的严格、确定性 JSON codec。
 *
 * <p>command type 本身不编码：durable {@code command_type} 单列与 HTTP DTO 外层 discriminator 负责类型。
 * USER/CUSTOM 的 {@code message} 子树委派 {@link AgentMessageJsonCodec}；SET_EXECUTION 携带 Driver
 * 与设置；SET_ENVIRONMENT 携带 nullable Environment 名（null 表示解除 环境选择）。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION}）。字段顺序固定；list 顺序保留。
 */
public final class ThreadCommandPayloadJsonCodec {

  private static final ObjectMapper MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> USER_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> SET_AGENT_FIELDS = orderedSet("agentName");
  private static final Set<String> SET_EXECUTION_FIELDS = orderedSet("execution");
  private static final Set<String> SET_ENVIRONMENT_FIELDS = orderedSet("environmentName");
  private static final Set<String> NOTIFICATION_FIELDS =
      orderedSet("notificationId", "kind", "sourceThreadId", "message");

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  private static final ThreadCommandPayloadJsonCodec REQUEST_HASH_CODEC =
      new ThreadCommandPayloadJsonCodec();

  public ThreadCommandPayloadJsonCodec() {}

  /** 把 {@link ThreadCommandPayload} 编码为 canonical JSON 文本；不包含 command type discriminator。 */
  public String encode(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeNode(payload));
  }

  /**
   * 计算 client command 的 canonical request hash：SHA-256（小写 hex，64 字符）作用于显式 canonical 信封 {@code
   * {"type":<ThreadCommandType>,"payload":<request payload>}}。
   *
   * <p>信封同时包含 command type 与 raw 请求 payload（含 ordered contents 与 uploadId），因此不同 type 的 payload 即使
   * JSON 形状相同也不会碰撞；同一 raw 请求永远得到同一 hash；durable 表示（如附件已物化为 RESOURCE）不影响 hash。
   */
  public static String requestHash(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    byte[] canonical = write(encodeRequestEnvelopeNode(payload)).getBytes(StandardCharsets.UTF_8);
    return Sha256.hex(canonical);
  }

  /** 编码 request hash 的 canonical 信封节点：{@code type}（command discriminator）+ {@code payload}。 */
  private static ObjectNode encodeRequestEnvelopeNode(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    ObjectNode envelope = NODES.objectNode();
    envelope.put("type", payload.type().name());
    envelope.set("payload", encodeRequestNode(payload));
    return envelope;
  }

  /**
   * 把 canonical JSON 文本按指定 {@link ThreadCommandType} 解码为对应 payload；任何非法结构抛 {@link
   * IllegalArgumentException}。
   */
  public ThreadCommandPayload decode(ThreadCommandType type, String json) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new IllegalArgumentException(
          "malformed " + type.name() + " command payload JSON", error);
    }
    // Jackson 3 空白输入返回 MissingNode（Jackson 2 返回 null），两者都必须判为 malformed。
    if (root == null || root.isMissingNode()) {
      throw new IllegalArgumentException("malformed " + type.name() + " command payload JSON");
    }
    return switch (type) {
      case USER_MESSAGE -> decodeUserMessage(root);
      case CUSTOM_MESSAGE -> decodeCustomMessage(root);
      case SET_AGENT -> decodeSetAgent(root);
      case SET_EXECUTION -> decodeSetExecution(root);
      case SET_ENVIRONMENT -> decodeSetEnvironment(root);
      case NOTIFICATION -> decodeNotification(root);
    };
  }

  // ---------- 编码器 ----------

  /**
   * 把 {@link ThreadCommandPayload} 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。
   */
  public ObjectNode encodeNode(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return encodeNode(payload, false);
  }

  /** 请求形态编码：USER/CUSTOM message 内容使用支持瞬时 ATTACHMENT 的请求 codec。 */
  private static ObjectNode encodeRequestNode(ThreadCommandPayload payload) {
    return encodeNode(payload, true);
  }

  private static ObjectNode encodeNode(ThreadCommandPayload payload, boolean requestForm) {
    return switch (payload) {
      case UserMessageCommandPayload value -> NODES
          .objectNode()
          .set(
              "message",
              requestForm
                  ? MESSAGE_CODEC.encodeRequestNode(value.message())
                  : MESSAGE_CODEC.encodeNode(value.message()));
        // CUSTOM_MESSAGE 永远使用 durable message 形态：request hash 同样基于 durable 形态，
        // attachment / media 内容在 durable codec 中确定性拒绝。
      case CustomMessageCommandPayload value -> NODES
          .objectNode()
          .set("message", MESSAGE_CODEC.encodeNode(value.message()));
      case SetAgentCommandPayload value -> NODES.objectNode().put("agentName", value.agentName());
      case SetExecutionCommandPayload value -> NODES
          .objectNode()
          .set("execution", ExecutionSelectionJsonCodec.encode(value.execution()));
      case SetEnvironmentCommandPayload value -> {
        ObjectNode node = NODES.objectNode();
        // environmentName 必须显式存在：null 表示解除环境选择，与「字段缺失」严格区分。
        if (value.environmentName() == null) {
          node.putNull("environmentName");
        } else {
          node.put("environmentName", value.environmentName());
        }
        yield node;
      }
      case NotificationCommandPayload value -> {
        ObjectNode node = NODES.objectNode();
        node.put("notificationId", value.notificationId().toString());
        node.put("kind", value.kind().name());
        node.put("sourceThreadId", value.sourceThreadId().toString());
        node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
        yield node;
      }
    };
  }

  // ---------- 解码器 ----------

  private static UserMessageCommandPayload decodeUserMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "USER_MESSAGE");
    requireFields(node, USER_MESSAGE_FIELDS, "USER_MESSAGE");
    return new UserMessageCommandPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static CustomMessageCommandPayload decodeCustomMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "CUSTOM_MESSAGE");
    requireFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessageCommandPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static SetAgentCommandPayload decodeSetAgent(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_AGENT");
    requireFields(node, SET_AGENT_FIELDS, "SET_AGENT");
    return new SetAgentCommandPayload(canonicalText(node, "agentName", "SET_AGENT"));
  }

  private static SetExecutionCommandPayload decodeSetExecution(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_EXECUTION");
    requireFields(node, SET_EXECUTION_FIELDS, "SET_EXECUTION");
    return new SetExecutionCommandPayload(
        ExecutionSelectionJsonCodec.decode(node.get("execution"), "SET_EXECUTION.execution"));
  }

  private static SetEnvironmentCommandPayload decodeSetEnvironment(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_ENVIRONMENT");
    requireFields(node, SET_ENVIRONMENT_FIELDS, "SET_ENVIRONMENT");
    return new SetEnvironmentCommandPayload(
        nullableEnvironmentName(node, "environmentName", "SET_ENVIRONMENT"));
  }

  /** 解码可空 Environment 名：text 或 null；其他类型（含对象/数组）确定性拒绝。 */
  private static String nullableEnvironmentName(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    return CommandValueValidation.requireCanonicalEnvironmentName(
        value.stringValue(), context + "." + field);
  }

  private static NotificationCommandPayload decodeNotification(JsonNode value) {
    ObjectNode node = requireObject(value, "NOTIFICATION");
    requireFields(node, NOTIFICATION_FIELDS, "NOTIFICATION");
    return new NotificationCommandPayload(
        requiredUuid(node, "notificationId", "NOTIFICATION"),
        requiredNotificationKind(node, "kind", "NOTIFICATION"),
        requiredUuid(node, "sourceThreadId", "NOTIFICATION"),
        MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static UUID requiredUuid(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be a UUID string");
    }
    try {
      return Uuids.parseCanonical(value.stringValue(), context + "." + field);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(context + "." + field + " must be a UUID string", error);
    }
  }

  private static NotificationKind requiredNotificationKind(
      ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be a notification kind");
    }
    try {
      return NotificationKind.valueOf(value.stringValue());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a notification kind", error);
    }
  }

  private static int requiredNonNegativeInt(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-negative integer");
    }
    return value.intValue();
  }

  // ---------- 通用工具方法 ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode thread command payload JSON", error);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String context) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return object;
  }

  private static String canonicalText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return CommandValueValidation.requireCanonicalName(value.stringValue(), context + "." + field);
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Set.copyOf(set);
  }
}
