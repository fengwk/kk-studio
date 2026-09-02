package fun.fengwk.kkstudio.harness.runtime.thread.command;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 5 类 typed Thread command payload 的严格、确定性 JSON codec。
 *
 * <p>command type 本身不编码：durable {@code command_type} 单列与 HTTP DTO 外层 discriminator 负责类型。
 * USER/CUSTOM 的 {@code message} 子树委派 {@link AgentMessageJsonCodec}；SET_MODEL 携带完整 {@link
 * ModelSelection}；SET_ENVIRONMENT 的 {@code workspacePath} 为可空 canonical 相对路径字符串（null 表示 clear）。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）。字段顺序固定；list 顺序保留。
 */
public final class ThreadCommandPayloadJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> USER_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> SET_AGENT_FIELDS = orderedSet("agentName");
  private static final Set<String> SET_MODEL_FIELDS = orderedSet("model");
  private static final Set<String> SET_ENVIRONMENT_FIELDS = orderedSet("workspacePath");
  private static final Set<String> MODEL_SELECTION_FIELDS =
      orderedSet("providerName", "modelName", "variant");

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  private static final ThreadCommandPayloadJsonCodec REQUEST_HASH_CODEC =
      new ThreadCommandPayloadJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ThreadCommandPayloadJsonCodec() {}

  /** 把 {@link ThreadCommandPayload} 编码为 canonical JSON 文本；不包含 command type discriminator。 */
  public String encode(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeNode(payload));
  }

  /**
   * 把 {@link ThreadCommandPayload} 编码为 client command 请求的 canonical JSON 文本：与 {@link #encode} 相同， 但
   * USER message 内容允许瞬时 {@code attachment} 单元（含 ordered uploadId）。CUSTOM_MESSAGE 永远使用 durable
   * 形态（attachment / media 内容确定性拒绝）。请求 hash 只基于该形态计算。
   */
  public String encodeRequest(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeRequestNode(payload));
  }

  /**
   * 计算 client command 的 canonical request hash：SHA-256（小写 hex，64 字符）作用于显式 canonical 信封 {@code
   * {"type":<ThreadCommandType>,"payload":<encodeRequest>}}。
   *
   * <p>信封同时包含 command type 与 raw 请求 payload（含 ordered contents 与 uploadId），因此不同 type 的 payload 即使
   * JSON 形状相同也不会碰撞；同一 raw 请求永远得到同一 hash；durable 表示（如附件已物化为 RESOURCE）不影响 hash。
   */
  public static String requestHash(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    byte[] canonical = write(encodeRequestEnvelopeNode(payload)).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  /** 编码 request hash 的 canonical 信封节点：{@code type}（command discriminator）+ {@code payload}。 */
  public static ObjectNode encodeRequestEnvelopeNode(ThreadCommandPayload payload) {
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
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(
          "malformed " + type.name() + " command payload JSON", error);
    }
    if (root == null) {
      throw new IllegalArgumentException("malformed " + type.name() + " command payload JSON");
    }
    return switch (type) {
      case USER_MESSAGE -> decodeUserMessage(root);
      case CUSTOM_MESSAGE -> decodeCustomMessage(root);
      case SET_AGENT -> decodeSetAgent(root);
      case SET_MODEL -> decodeSetModel(root);
      case SET_ENVIRONMENT -> decodeSetEnvironment(root);
    };
  }

  // ---------- 编码器 ----------

  private static ObjectNode encodeNode(ThreadCommandPayload payload) {
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
      case SetModelCommandPayload value -> NODES
          .objectNode()
          .set("model", encodeModelSelection(value.model()));
      case SetEnvironmentCommandPayload value -> {
        ObjectNode node = NODES.objectNode();
        if (value.workspacePath() == null) {
          node.putNull("workspacePath");
        } else {
          node.put("workspacePath", value.workspacePath());
        }
        yield node;
      }
    };
  }

  private static ObjectNode encodeModelSelection(ModelSelection selection) {
    ObjectNode node = NODES.objectNode();
    node.put("providerName", selection.providerName());
    node.put("modelName", selection.modelName());
    node.put("variant", selection.variant());
    return node;
  }

  // ---------- 解码器 ----------

  private static UserMessageCommandPayload decodeUserMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "USER_MESSAGE");
    requireExactFields(node, USER_MESSAGE_FIELDS, "USER_MESSAGE");
    return new UserMessageCommandPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static CustomMessageCommandPayload decodeCustomMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "CUSTOM_MESSAGE");
    requireExactFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessageCommandPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static SetAgentCommandPayload decodeSetAgent(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_AGENT");
    requireExactFields(node, SET_AGENT_FIELDS, "SET_AGENT");
    return new SetAgentCommandPayload(canonicalText(node, "agentName", "SET_AGENT"));
  }

  private static SetModelCommandPayload decodeSetModel(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_MODEL");
    requireExactFields(node, SET_MODEL_FIELDS, "SET_MODEL");
    return new SetModelCommandPayload(decodeModelSelection(node.get("model")));
  }

  private static SetEnvironmentCommandPayload decodeSetEnvironment(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_ENVIRONMENT");
    requireExactFields(node, SET_ENVIRONMENT_FIELDS, "SET_ENVIRONMENT");
    JsonNode workspacePath = node.get("workspacePath");
    if (workspacePath.isNull()) {
      return new SetEnvironmentCommandPayload(null);
    }
    if (!workspacePath.isTextual()) {
      throw new IllegalArgumentException("SET_ENVIRONMENT.workspacePath must be a string or null");
    }
    // canonical 相对 wire 路径形状由 EnvironmentWorkspacePath 校验器统一约束。
    return new SetEnvironmentCommandPayload(
        EnvironmentWorkspacePath.requireCanonicalRelativePath(workspacePath.textValue()));
  }

  private static ModelSelection decodeModelSelection(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_MODEL.model");
    requireExactFields(node, MODEL_SELECTION_FIELDS, "SET_MODEL.model");
    return new ModelSelection(
        canonicalText(node, "providerName", "SET_MODEL.model"),
        canonicalText(node, "modelName", "SET_MODEL.model"),
        canonicalText(node, "variant", "SET_MODEL.model"));
  }

  // ---------- 通用工具方法 ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
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
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return CommandValueValidation.requireCanonicalName(value.textValue(), context + "." + field);
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new LinkedHashSet<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      actual.add(names.next());
    }
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          context + " unexpected fields: " + actual + " (expected " + expected + ")");
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Set.copyOf(set);
  }
}
