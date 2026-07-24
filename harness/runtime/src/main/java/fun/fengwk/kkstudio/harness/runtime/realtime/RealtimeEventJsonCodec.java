package fun.fengwk.kkstudio.harness.runtime.realtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 有界 realtime projection 的确定性 strict JSON codec。
 *
 * <p>支持 Model delta 与 Tool partial；top-level exact field set {@code {threadId, subjectKind,
 * subjectId, attempt, type, payload, createdAt}}。{@code attempt} 只出现在 top-level。
 *
 * <p>{@code payload} discriminator 严格大小写：
 *
 * <ul>
 *   <li>{@code TEXT_DELTA}: {@code {kind, text}}
 *   <li>{@code THINKING_DELTA}: {@code {kind, text}}
 *   <li>{@code TOOL_CALL_DELTA}: {@code {kind, index, id, name, argumentsJson}}；{@code id} / {@code
 *       name} / {@code argumentsJson} 显式允许 null；{@code argumentsJson} 是 raw string，不按完整 JSON 解析
 * </ul>
 *
 * <p>任何 duplicate / trailing / unknown / missing / wrong-type 字段被拒绝；不允许 Jackson polymorphic typing
 * 或 default typing。{@link ProviderStreamEvent.TextDelta} / {@link
 * ProviderStreamEvent.ThinkingDelta} / {@link ProviderStreamEvent.ToolCallDelta} 构造器不变量在 decode
 * 时被传播。
 */
public final class RealtimeEventJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> EVENT_FIELDS =
      orderedSet("threadId", "subjectKind", "subjectId", "attempt", "type", "payload", "createdAt");

  private static final Set<String> TEXT_PAYLOAD_FIELDS = orderedSet("kind", "text");
  private static final Set<String> THINKING_PAYLOAD_FIELDS = orderedSet("kind", "text");
  private static final Set<String> TOOL_CALL_PAYLOAD_FIELDS =
      orderedSet("kind", "index", "id", "name", "argumentsJson");

  private static final String MODEL_INVOCATION = ExecutionTargetKind.MODEL_INVOCATION.name();
  private static final String TOOL_INVOCATION = ExecutionTargetKind.TOOL_INVOCATION.name();
  private static final String MODEL_DELTA = RealtimeEventType.MODEL_DELTA.name();
  private static final String TOOL_PARTIAL = RealtimeEventType.TOOL_PARTIAL.name();

  private static final String TEXT_DELTA = "TEXT_DELTA";
  private static final String THINKING_DELTA = "THINKING_DELTA";
  private static final String TOOL_CALL_DELTA = "TOOL_CALL_DELTA";

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public RealtimeEventJsonCodec() {}

  /** 把 {@link RealtimeEvent} 编码为 canonical JSON 文本。 */
  public String encode(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    return write(encodeNode(event));
  }

  /** 把 {@link RealtimeEvent} 编码为 deterministic canonical {@link ObjectNode}。 */
  public ObjectNode encodeNode(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    if (event instanceof RealtimeEvent.ModelDelta delta) {
      return encodeModelDelta(delta);
    }
    if (event instanceof RealtimeEvent.ToolPartial partial) {
      return encodeToolPartial(partial);
    }
    throw new IllegalArgumentException("unsupported realtime event: " + event.getClass().getName());
  }

  /** 从 canonical JSON 文本解码为 {@link RealtimeEvent}。 */
  public RealtimeEvent decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed realtime event JSON", error);
    }
    return decodeNode(root);
  }

  /** 从任意 {@link JsonNode} 解码为 {@link RealtimeEvent}。 */
  public RealtimeEvent decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = requireObject(value, "realtimeEvent");
    requireExactFields(node, EVENT_FIELDS, "realtimeEvent");

    long threadId = requiredPositiveLong(node, "threadId", "realtimeEvent");
    String subjectKindName = requiredText(node, "subjectKind", "realtimeEvent");
    ExecutionTargetKind subjectKind =
        readEnum(ExecutionTargetKind.class, subjectKindName, "realtimeEvent.subjectKind");
    long subjectId = requiredPositiveLongString(node, "subjectId", "realtimeEvent");
    int attempt = requiredPositiveInt(node, "attempt", "realtimeEvent");
    String typeName = requiredText(node, "type", "realtimeEvent");
    RealtimeEventType type = readEnum(RealtimeEventType.class, typeName, "realtimeEvent.type");
    JsonNode payloadNode = node.get("payload");
    if (payloadNode == null || !payloadNode.isObject()) {
      throw new IllegalArgumentException("realtimeEvent.payload must be a JSON object");
    }
    String createdAtText = requiredText(node, "createdAt", "realtimeEvent");
    Instant createdAt;
    try {
      createdAt = Instant.parse(createdAtText);
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException(
          "realtimeEvent.createdAt must be an ISO-8601 instant string", error);
    }

    if (type == RealtimeEventType.MODEL_DELTA) {
      if (subjectKind != ExecutionTargetKind.MODEL_INVOCATION) {
        throw new IllegalArgumentException(
            "MODEL_DELTA subjectKind must be MODEL_INVOCATION: " + subjectKindName);
      }
      ProviderStreamEvent delta = decodePayload((ObjectNode) payloadNode);
      return new RealtimeEvent.ModelDelta(threadId, subjectId, attempt, delta, createdAt);
    }
    if (type == RealtimeEventType.TOOL_PARTIAL) {
      if (subjectKind != ExecutionTargetKind.TOOL_INVOCATION) {
        throw new IllegalArgumentException(
            "TOOL_PARTIAL subjectKind must be TOOL_INVOCATION: " + subjectKindName);
      }
      ToolResult partial = ToolResultJsonCodec.decode(write((ObjectNode) payloadNode));
      return new RealtimeEvent.ToolPartial(threadId, subjectId, attempt, partial, createdAt);
    }
    throw new IllegalArgumentException("unsupported realtimeEvent.type: " + typeName);
  }

  // ---------- Encoders ----------

  private static ObjectNode encodeModelDelta(RealtimeEvent.ModelDelta delta) {
    ObjectNode node = NODES.objectNode();
    node.put("threadId", Long.toString(delta.threadId()));
    node.put("subjectKind", MODEL_INVOCATION);
    node.put("subjectId", Long.toString(delta.modelInvocationId()));
    node.put("attempt", delta.attempt());
    node.put("type", MODEL_DELTA);
    node.set("payload", encodePayload(delta.delta()));
    node.put("createdAt", delta.createdAt().toString());
    return node;
  }

  private static ObjectNode encodeToolPartial(RealtimeEvent.ToolPartial partial) {
    ObjectNode node = NODES.objectNode();
    node.put("threadId", Long.toString(partial.threadId()));
    node.put("subjectKind", TOOL_INVOCATION);
    node.put("subjectId", Long.toString(partial.toolInvocationId()));
    node.put("attempt", partial.attempt());
    node.put("type", TOOL_PARTIAL);
    node.set("payload", ToolResultJsonCodec.encodeNode(partial.partial()));
    node.put("createdAt", partial.createdAt().toString());
    return node;
  }

  private static ObjectNode encodePayload(ProviderStreamEvent delta) {
    ObjectNode node = NODES.objectNode();
    if (delta instanceof ProviderStreamEvent.TextDelta value) {
      node.put("kind", TEXT_DELTA);
      node.put("text", value.text());
    } else if (delta instanceof ProviderStreamEvent.ThinkingDelta value) {
      node.put("kind", THINKING_DELTA);
      node.put("text", value.text());
    } else if (delta instanceof ProviderStreamEvent.ToolCallDelta value) {
      node.put("kind", TOOL_CALL_DELTA);
      node.put("index", value.index());
      if (value.id() == null) {
        node.putNull("id");
      } else {
        node.put("id", value.id());
      }
      if (value.name() == null) {
        node.putNull("name");
      } else {
        node.put("name", value.name());
      }
      if (value.argumentsJson() == null) {
        node.putNull("argumentsJson");
      } else {
        node.put("argumentsJson", value.argumentsJson());
      }
    } else {
      throw new IllegalArgumentException(
          "unsupported provider stream event: " + delta.getClass().getName());
    }
    return node;
  }

  // ---------- Decoders ----------

  private static ProviderStreamEvent decodePayload(ObjectNode node) {
    JsonNode kindNode = node.get("kind");
    if (kindNode == null || !kindNode.isTextual()) {
      throw new IllegalArgumentException("realtimeEvent.payload.kind must be text");
    }
    String kind = kindNode.textValue();
    return switch (kind) {
      case TEXT_DELTA -> {
        requireExactFields(node, TEXT_PAYLOAD_FIELDS, "realtimeEvent.payload");
        yield new ProviderStreamEvent.TextDelta(
            requiredText(node, "text", "realtimeEvent.payload"));
      }
      case THINKING_DELTA -> {
        requireExactFields(node, THINKING_PAYLOAD_FIELDS, "realtimeEvent.payload");
        yield new ProviderStreamEvent.ThinkingDelta(
            requiredText(node, "text", "realtimeEvent.payload"));
      }
      case TOOL_CALL_DELTA -> {
        requireExactFields(node, TOOL_CALL_PAYLOAD_FIELDS, "realtimeEvent.payload");
        int index = requiredNonNegativeInt(node, "index", "realtimeEvent.payload");
        JsonNode idNode = node.get("id");
        String id = decodeNullableText(idNode, "id", "realtimeEvent.payload");
        JsonNode nameNode = node.get("name");
        String name = decodeNullableText(nameNode, "name", "realtimeEvent.payload");
        JsonNode argumentsJsonNode = node.get("argumentsJson");
        String argumentsJson =
            decodeNullableText(argumentsJsonNode, "argumentsJson", "realtimeEvent.payload");
        yield new ProviderStreamEvent.ToolCallDelta(index, id, name, argumentsJson);
      }
      default -> throw new IllegalArgumentException("unknown payload kind: " + kind);
    };
  }

  // ---------- Helpers ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode realtime event JSON", error);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String context) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return object;
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.textValue();
  }

  private static String decodeNullableText(JsonNode value, String field, String context) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    return value.textValue();
  }

  private static long requiredPositiveLong(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a positive decimal string");
    }
    String text = value.textValue();
    if (text.isEmpty() || text.charAt(0) == '+' || text.charAt(0) == '-') {
      throw new IllegalArgumentException(
          context + "." + field + " must be a positive decimal string");
    }
    long parsed;
    try {
      parsed = Long.parseLong(text);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a positive decimal string", error);
    }
    if (parsed <= 0) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a positive decimal string");
    }
    return parsed;
  }

  private static long requiredPositiveLongString(ObjectNode node, String field, String context) {
    return requiredPositiveLong(node, field, context);
  }

  private static int requiredPositiveInt(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a positive integer");
    }
    return value.intValue();
  }

  private static int requiredNonNegativeInt(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-negative integer");
    }
    return value.intValue();
  }

  private static <E extends Enum<E>> E readEnum(Class<E> kind, String name, String context) {
    try {
      return Enum.valueOf(kind, name);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IllegalArgumentException(
          context
              + " must be one of "
              + kind.getEnumConstants().length
              + " "
              + kind.getSimpleName()
              + " values: "
              + name,
          error);
    }
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
