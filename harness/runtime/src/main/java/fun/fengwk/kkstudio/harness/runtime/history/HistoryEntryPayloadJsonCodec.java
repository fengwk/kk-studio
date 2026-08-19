package fun.fengwk.kkstudio.harness.runtime.history;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 最终 10 类 history Entry payload 的严格、确定性 JSON codec：{@link RootPayload} / {@link TurnStartPayload} /
 * {@link MessagePayload} / {@link CustomEntryPayload} / {@link ModelAttemptFailurePayload} / {@link
 * CustomMessagePayload} / {@link AssistantErrorPayload} / {@link AssistantAbortedPayload} / {@link
 * CompactionPayload} / {@link TurnEndPayload}。直接对应 {@link EntryType}；其它 {@link EntryPayload}
 * 实现显式拒绝。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）；未知枚举；非 canonical 十进制 ID；非法完整 Environment
 * binding（{@code {name, workspacePath}}）。字段顺序固定；list 顺序保留。
 *
 * <p>{@code message} 子树委派 {@link AgentMessageJsonCodec}；settings / model / assistant metadata 子树
 * 委派包内 {@link HistoryValueCodecs}。
 */
public final class HistoryEntryPayloadJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> ROOT_FIELDS = orderedSet("settings", "subagentContext");
  private static final Set<String> SUBAGENT_CONTEXT_FIELDS =
      orderedSet("parentThreadId", "rootThreadId", "taskInvocationId", "depth");
  private static final Set<String> TURN_START_FIELDS =
      orderedSet("reason", "settings", "ownerThreadId", "contextWindow");
  private static final Set<String> MESSAGE_FIELDS =
      orderedSet("message", "assistantMetadata", "toolResultMetadata");
  private static final Set<String> CUSTOM_FIELDS =
      orderedSet("pluginId", "customType", "schemaVersion", "data");
  private static final Set<String> MODEL_ATTEMPT_FAILURE_FIELDS =
      orderedSet("attempt", "error", "retryAt");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS =
      orderedSet("pluginId", "customType", "rendererKey", "message", "details");
  private static final Set<String> ASSISTANT_ERROR_FIELDS = orderedSet("error", "attempt");
  private static final Set<String> ATTEMPT_SNAPSHOT_FIELDS =
      orderedSet("attempt", "sequence", "text", "thinking");
  private static final Set<String> ASSISTANT_ABORTED_FIELDS = orderedSet("message");
  private static final Set<String> COMPACTION_FIELDS =
      orderedSet(
          "phase",
          "trigger",
          "tokensBefore",
          "complete",
          "summaryText",
          "firstKeptEntryId",
          "cutEntryId",
          "turnPrefixStartEntryId");
  private static final Set<String> TURN_END_FIELDS =
      orderedSet("turnStartEntryId", "outcome", "continueModel", "reason", "closeRequestId");
  private static final Set<String> ERROR_FIELDS = orderedSet("code", "message");
  private static final Set<String> TOOL_RESULT_METADATA_FIELDS =
      orderedSet("assistantEntryId", "toolCallId", "ordinal", "status", "synthetic", "reason");

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public HistoryEntryPayloadJsonCodec() {}

  /** 把 {@link EntryPayload} 编码为 canonical JSON 文本；仅支持最终 10 类 history payload，其它实现显式拒绝。 */
  public String encode(EntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeNode(payload));
  }

  /** 把 {@link EntryPayload} 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。 */
  public ObjectNode encodeNode(EntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return switch (payload) {
      case RootPayload value -> encodeRoot(value);
      case TurnStartPayload value -> encodeTurnStart(value);
      case MessagePayload value -> encodeMessage(value);
      case CustomEntryPayload value -> encodeCustomEntry(value);
      case ModelAttemptFailurePayload value -> encodeModelAttemptFailure(value);
      case CustomMessagePayload value -> encodeCustomMessage(value);
      case AssistantErrorPayload value -> encodeAssistantError(value);
      case AssistantAbortedPayload value -> encodeAssistantAborted(value);
      case CompactionPayload value -> encodeCompaction(value);
      case TurnEndPayload value -> encodeTurnEnd(value);
    };
  }

  /**
   * 把 canonical JSON 文本按指定 {@link EntryType} 解码为对应 payload；任何非法结构抛 {@link
   * IllegalArgumentException}。
   */
  public EntryPayload decode(EntryType type, String json) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed " + type.name() + " entry payload JSON", error);
    }
    if (root == null) {
      throw new IllegalArgumentException("malformed " + type.name() + " entry payload JSON");
    }
    return decodeNode(type, root);
  }

  /** 从任意 {@link JsonNode} 按 {@link EntryType} 解码 payload。 */
  public EntryPayload decodeNode(EntryType type, JsonNode value) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
    return switch (type) {
      case ROOT -> decodeRoot(value);
      case TURN_START -> decodeTurnStart(value);
      case MESSAGE -> decodeMessage(value);
      case CUSTOM -> decodeCustomEntry(value);
      case MODEL_ATTEMPT_FAILURE -> decodeModelAttemptFailure(value);
      case CUSTOM_MESSAGE -> decodeCustomMessage(value);
      case ASSISTANT_ERROR -> decodeAssistantError(value);
      case ASSISTANT_ABORTED -> decodeAssistantAborted(value);
      case COMPACTION -> decodeCompaction(value);
      case TURN_END -> decodeTurnEnd(value);
    };
  }

  // ---------- 编码器 ----------

  private static ObjectNode encodeRoot(RootPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("settings", HistoryValueCodecs.encodeBranchSettings(value.settings()));
    if (value.subagentContext() == null) {
      node.putNull("subagentContext");
    } else {
      node.set("subagentContext", encodeSubagentContext(value.subagentContext()));
    }
    return node;
  }

  private static ObjectNode encodeSubagentContext(SubagentContext value) {
    ObjectNode node = NODES.objectNode();
    node.put("parentThreadId", value.parentThreadId().toString());
    node.put("rootThreadId", value.rootThreadId().toString());
    node.put("taskInvocationId", value.taskInvocationId().toString());
    node.put("depth", value.depth());
    return node;
  }

  private static ObjectNode encodeTurnStart(TurnStartPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("reason", value.reason().name());
    node.set("settings", HistoryValueCodecs.encodeBranchSettings(value.settings()));
    node.put("ownerThreadId", value.ownerThreadId().toString());
    if (value.contextWindow() == null) {
      node.putNull("contextWindow");
    } else {
      node.put("contextWindow", value.contextWindow());
    }
    return node;
  }

  private static ObjectNode encodeMessage(MessagePayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    if (value.assistantMetadata() == null) {
      node.putNull("assistantMetadata");
    } else {
      node.set(
          "assistantMetadata",
          HistoryValueCodecs.encodeAssistantMetadata(value.assistantMetadata()));
    }
    if (value.toolResultMetadata() == null) {
      node.putNull("toolResultMetadata");
    } else {
      node.set("toolResultMetadata", encodeToolResultMetadata(value.toolResultMetadata()));
    }
    return node;
  }

  private static ObjectNode encodeCustomEntry(CustomEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("pluginId", value.pluginId());
    node.put("customType", value.customType());
    node.put("schemaVersion", value.schemaVersion());
    node.set("data", JsonObjects.parseObject(value.dataJson(), "data"));
    return node;
  }

  private static ObjectNode encodeModelAttemptFailure(ModelAttemptFailurePayload value) {
    ObjectNode node = NODES.objectNode();
    ModelAttemptSnapshot attempt = value.attempt();
    ObjectNode attemptNode = NODES.objectNode();
    attemptNode.put("attempt", attempt.attempt());
    attemptNode.put("sequence", attempt.sequence());
    attemptNode.put("text", attempt.text());
    attemptNode.put("thinking", attempt.thinking());
    node.set("attempt", attemptNode);
    node.set("error", encodeError(value.error()));
    node.put("retryAt", value.retryAt().toString());
    return node;
  }

  private static ObjectNode encodeCustomMessage(CustomMessagePayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("pluginId", value.pluginId());
    node.put("customType", value.customType());
    node.put("rendererKey", value.rendererKey());
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    node.set("details", JsonObjects.parseObject(value.detailsJson(), "details"));
    return node;
  }

  private static ObjectNode encodeAssistantError(AssistantErrorPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("error", encodeError(value.error()));
    if (value.attempt() == null) {
      node.putNull("attempt");
    } else {
      ObjectNode attempt = NODES.objectNode();
      attempt.put("attempt", value.attempt().attempt());
      attempt.put("sequence", value.attempt().sequence());
      attempt.put("text", value.attempt().text());
      attempt.put("thinking", value.attempt().thinking());
      node.set("attempt", attempt);
    }
    return node;
  }

  private static ObjectNode encodeAssistantAborted(AssistantAbortedPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    return node;
  }

  private static ObjectNode encodeCompaction(CompactionPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("phase", value.phase().name());
    node.put("trigger", value.trigger().name());
    node.put("tokensBefore", value.tokensBefore());
    node.put("complete", value.complete());
    node.put("summaryText", value.summaryText());
    node.put("firstKeptEntryId", value.firstKeptEntryId().toString());
    node.put("cutEntryId", value.cutEntryId().toString());
    if (value.turnPrefixStartEntryId() == null) {
      node.putNull("turnPrefixStartEntryId");
    } else {
      node.put("turnPrefixStartEntryId", value.turnPrefixStartEntryId().toString());
    }
    return node;
  }

  private static ObjectNode encodeTurnEnd(TurnEndPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("turnStartEntryId", value.turnStartEntryId().toString());
    node.put("outcome", value.outcome().name());
    node.put("continueModel", value.continueModel());
    if (value.reason() == null) {
      node.putNull("reason");
    } else {
      node.put("reason", value.reason().name());
    }
    if (value.closeRequestId() == null) {
      node.putNull("closeRequestId");
    } else {
      node.put("closeRequestId", value.closeRequestId().toString());
    }
    return node;
  }

  private static ObjectNode encodeError(AssistantError error) {
    ObjectNode node = NODES.objectNode();
    node.put("code", error.code());
    node.put("message", error.message());
    return node;
  }

  private static ObjectNode encodeToolResultMetadata(ToolResultMetadata metadata) {
    ObjectNode node = NODES.objectNode();
    node.put("assistantEntryId", metadata.assistantEntryId().toString());
    node.put("toolCallId", metadata.toolCallId());
    node.put("ordinal", metadata.ordinal());
    node.put("status", metadata.status().name());
    node.put("synthetic", metadata.synthetic());
    if (metadata.reason() == null) {
      node.putNull("reason");
    } else {
      node.put("reason", metadata.reason().name());
    }
    return node;
  }

  // ---------- 解码器 ----------

  private static RootPayload decodeRoot(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ROOT");
    HistoryValueCodecs.requireExactFields(node, ROOT_FIELDS, "ROOT");
    return new RootPayload(
        HistoryValueCodecs.decodeBranchSettings(node.get("settings"), "ROOT.settings"),
        decodeNullableSubagentContext(node.get("subagentContext")));
  }

  private static SubagentContext decodeNullableSubagentContext(JsonNode value) {
    if (value.isNull()) {
      return null;
    }
    String context = "ROOT.subagentContext";
    ObjectNode node = HistoryValueCodecs.requireObject(value, context);
    HistoryValueCodecs.requireExactFields(node, SUBAGENT_CONTEXT_FIELDS, context);
    return new SubagentContext(
        HistoryValueCodecs.requiredPositiveId(node, "parentThreadId", context),
        HistoryValueCodecs.requiredPositiveId(node, "rootThreadId", context),
        HistoryValueCodecs.requiredPositiveId(node, "taskInvocationId", context),
        HistoryValueCodecs.requiredNonNegativeInt(node, "depth", context));
  }

  private static TurnStartPayload decodeTurnStart(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "TURN_START");
    HistoryValueCodecs.requireExactFields(node, TURN_START_FIELDS, "TURN_START");
    return new TurnStartPayload(
        HistoryValueCodecs.readEnum(
            TurnStartReason.class, HistoryValueCodecs.text(node, "reason"), "TURN_START.reason"),
        HistoryValueCodecs.decodeBranchSettings(node.get("settings"), "TURN_START.settings"),
        HistoryValueCodecs.requiredPositiveId(node, "ownerThreadId", "TURN_START"),
        HistoryValueCodecs.nullablePositiveInt(node, "contextWindow", "TURN_START"));
  }

  private static MessagePayload decodeMessage(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "MESSAGE");
    HistoryValueCodecs.requireExactFields(node, MESSAGE_FIELDS, "MESSAGE");
    AgentMessage message = MESSAGE_CODEC.decodeNode(node.get("message"));
    JsonNode assistantMetadata = node.get("assistantMetadata");
    AssistantMessageMetadata metadata =
        assistantMetadata.isNull()
            ? null
            : HistoryValueCodecs.decodeAssistantMetadata(assistantMetadata);
    JsonNode toolResultMetadata = node.get("toolResultMetadata");
    ToolResultMetadata toolResult =
        toolResultMetadata.isNull() ? null : decodeToolResultMetadata(toolResultMetadata);
    return new MessagePayload(message, metadata, toolResult);
  }

  private static CustomEntryPayload decodeCustomEntry(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "CUSTOM");
    HistoryValueCodecs.requireExactFields(node, CUSTOM_FIELDS, "CUSTOM");
    return new CustomEntryPayload(
        HistoryValueCodecs.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "pluginId"), "pluginId"),
        HistoryValueCodecs.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "customType"), "customType"),
        HistoryValueCodecs.requiredNonNegativeInt(node, "schemaVersion", "CUSTOM"),
        JsonObjects.write(HistoryValueCodecs.requireObject(node.get("data"), "CUSTOM.data")));
  }

  private static CustomMessagePayload decodeCustomMessage(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "CUSTOM_MESSAGE");
    HistoryValueCodecs.requireExactFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessagePayload(
        HistoryValueCodecs.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "pluginId"), "pluginId"),
        HistoryValueCodecs.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "customType"), "customType"),
        HistoryValueCodecs.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "rendererKey"), "rendererKey"),
        MESSAGE_CODEC.decodeNode(node.get("message")),
        JsonObjects.write(
            HistoryValueCodecs.requireObject(node.get("details"), "CUSTOM_MESSAGE.details")));
  }

  private static ModelAttemptFailurePayload decodeModelAttemptFailure(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "MODEL_ATTEMPT_FAILURE");
    HistoryValueCodecs.requireExactFields(
        node, MODEL_ATTEMPT_FAILURE_FIELDS, "MODEL_ATTEMPT_FAILURE");
    ObjectNode attemptNode =
        HistoryValueCodecs.requireObject(node.get("attempt"), "MODEL_ATTEMPT_FAILURE.attempt");
    HistoryValueCodecs.requireExactFields(
        attemptNode, ATTEMPT_SNAPSHOT_FIELDS, "MODEL_ATTEMPT_FAILURE.attempt");
    ModelAttemptSnapshot attempt =
        new ModelAttemptSnapshot(
            HistoryValueCodecs.requiredPositiveInt(
                attemptNode, "attempt", "MODEL_ATTEMPT_FAILURE.attempt"),
            HistoryValueCodecs.requiredNonNegativeLong(
                attemptNode, "sequence", "MODEL_ATTEMPT_FAILURE.attempt"),
            HistoryValueCodecs.text(attemptNode, "text"),
            HistoryValueCodecs.text(attemptNode, "thinking"));
    Instant retryAt;
    try {
      retryAt = Instant.parse(HistoryValueCodecs.text(node, "retryAt"));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "MODEL_ATTEMPT_FAILURE.retryAt must be an ISO-8601 instant", error);
    }
    return new ModelAttemptFailurePayload(attempt, decodeError(node.get("error")), retryAt);
  }

  private static AssistantErrorPayload decodeAssistantError(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ASSISTANT_ERROR");
    HistoryValueCodecs.requireExactFields(node, ASSISTANT_ERROR_FIELDS, "ASSISTANT_ERROR");
    JsonNode attempt = node.get("attempt");
    ModelAttemptSnapshot snapshot = null;
    if (!attempt.isNull()) {
      ObjectNode snapshotNode =
          HistoryValueCodecs.requireObject(attempt, "ASSISTANT_ERROR.attempt");
      HistoryValueCodecs.requireExactFields(
          snapshotNode, ATTEMPT_SNAPSHOT_FIELDS, "ASSISTANT_ERROR.attempt");
      snapshot =
          new ModelAttemptSnapshot(
              HistoryValueCodecs.requiredPositiveInt(
                  snapshotNode, "attempt", "ASSISTANT_ERROR.attempt"),
              HistoryValueCodecs.requiredNonNegativeLong(
                  snapshotNode, "sequence", "ASSISTANT_ERROR.attempt"),
              HistoryValueCodecs.text(snapshotNode, "text"),
              HistoryValueCodecs.text(snapshotNode, "thinking"));
    }
    return new AssistantErrorPayload(decodeError(node.get("error")), snapshot);
  }

  private static AssistantAbortedPayload decodeAssistantAborted(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ASSISTANT_ABORTED");
    HistoryValueCodecs.requireExactFields(node, ASSISTANT_ABORTED_FIELDS, "ASSISTANT_ABORTED");
    return new AssistantAbortedPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static CompactionPayload decodeCompaction(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "COMPACTION");
    HistoryValueCodecs.requireExactFields(node, COMPACTION_FIELDS, "COMPACTION");
    return new CompactionPayload(
        HistoryValueCodecs.readEnum(
            CompactionPhase.class, HistoryValueCodecs.text(node, "phase"), "COMPACTION.phase"),
        HistoryValueCodecs.readEnum(
            CompactionTrigger.class,
            HistoryValueCodecs.text(node, "trigger"),
            "COMPACTION.trigger"),
        HistoryValueCodecs.requiredNonNegativeLong(node, "tokensBefore", "COMPACTION"),
        HistoryValueCodecs.requiredBoolean(node, "complete", "COMPACTION"),
        HistoryValueCodecs.text(node, "summaryText"),
        HistoryValueCodecs.requiredPositiveId(node, "firstKeptEntryId", "COMPACTION"),
        HistoryValueCodecs.requiredPositiveId(node, "cutEntryId", "COMPACTION"),
        HistoryValueCodecs.nullablePositiveId(node, "turnPrefixStartEntryId", "COMPACTION"));
  }

  private static TurnEndPayload decodeTurnEnd(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "TURN_END");
    HistoryValueCodecs.requireExactFields(node, TURN_END_FIELDS, "TURN_END");
    return new TurnEndPayload(
        HistoryValueCodecs.requiredPositiveId(node, "turnStartEntryId", "TURN_END"),
        HistoryValueCodecs.readEnum(
            TurnEndOutcome.class, HistoryValueCodecs.text(node, "outcome"), "TURN_END.outcome"),
        HistoryValueCodecs.requiredBoolean(node, "continueModel", "TURN_END"),
        HistoryValueCodecs.nullableEnum(TurnEndReason.class, node, "reason", "TURN_END.reason"),
        HistoryValueCodecs.nullablePositiveId(node, "closeRequestId", "TURN_END"));
  }

  private static AssistantError decodeError(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ASSISTANT_ERROR.error");
    HistoryValueCodecs.requireExactFields(node, ERROR_FIELDS, "ASSISTANT_ERROR.error");
    return new AssistantError(
        HistoryValueCodecs.text(node, "code"), HistoryValueCodecs.text(node, "message"));
  }

  private static ToolResultMetadata decodeToolResultMetadata(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "toolResultMetadata");
    HistoryValueCodecs.requireExactFields(node, TOOL_RESULT_METADATA_FIELDS, "toolResultMetadata");
    return new ToolResultMetadata(
        HistoryValueCodecs.requiredPositiveId(node, "assistantEntryId", "toolResultMetadata"),
        HistoryValueCodecs.text(node, "toolCallId"),
        HistoryValueCodecs.requiredNonNegativeInt(node, "ordinal", "toolResultMetadata"),
        HistoryValueCodecs.readEnum(
            ToolResultStatus.class,
            HistoryValueCodecs.text(node, "status"),
            "toolResultMetadata.status"),
        HistoryValueCodecs.requiredBoolean(node, "synthetic", "toolResultMetadata"),
        HistoryValueCodecs.nullableEnum(
            ToolResultReason.class, node, "reason", "toolResultMetadata.reason"));
  }

  // ---------- 通用工具方法 ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode history entry payload JSON", error);
    }
  }

  private static Set<String> orderedSet(String... values) {
    return HistoryValueCodecs.orderedSet(values);
  }
}
