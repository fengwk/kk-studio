package fun.fengwk.kkstudio.harness.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CanonicalIdentifiers;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.history.ForkMode;
import fun.fengwk.kkstudio.harness.api.history.ForkPayload;
import fun.fengwk.kkstudio.harness.api.history.JsonObjects;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ModelAttemptFailurePayload;
import fun.fengwk.kkstudio.harness.api.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.api.history.NativeCompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationKind;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.history.SystemHostedTool;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultReason;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * history Entry payload 的严格、确定性 JSON codec：{@link RootPayload} / {@link TurnStartPayload} / {@link
 * MessagePayload} / {@link ModelAttemptFailurePayload} / {@link CustomMessagePayload} / {@link
 * AssistantErrorPayload} / {@link AssistantAbortedPayload} / {@link CompactionPayload} / {@link
 * TurnEndPayload} / {@link SettingsPayload}。直接对应 {@link EntryType}；其它 {@link EntryPayload} 实现显式拒绝。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION}）；未知枚举；非 canonical 十进制 ID；非法 {@code
 * environmentId}。字段顺序固定；list 顺序保留。
 *
 * <p>{@code message} 子树委派 {@link AgentMessageJsonCodec}；settings / model / assistant metadata 子树
 * 委派包内 {@link HistoryValueCodecs}。
 */
public final class HistoryEntryPayloadJsonCodec {

  private static final ObjectMapper MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> ROOT_FIELDS = orderedSet("settings");
  private static final Set<String> SYSTEM_FIELDS =
      orderedSet(
          "sections",
          "removedSections",
          "addedTools",
          "removedTools",
          "addedHostedTools",
          "removedHostedTools",
          "baseline");
  private static final Set<String> TURN_START_FIELDS =
      orderedSet(
          "reason",
          "settings",
          "ownerThreadId",
          "contextWindow",
          "maxOutputTokens",
          "compaction",
          "executedSelection",
          "capabilities");
  private static final Set<String> SYSTEM_HOSTED_TOOL_FIELDS = orderedSet("name", "definitionJson");
  private static final Set<String> TURN_CAPABILITIES_FIELDS =
      orderedSet("subagentBindings", "mediaCapabilities");
  private static final Set<String> SUBAGENT_BINDING_FIELDS = orderedSet("name", "description");
  private static final Set<String> TURN_MEDIA_CAPABILITIES_FIELDS =
      orderedSet("inputModalities", "userModalities", "toolResultModalities");
  private static final Set<String> MESSAGE_FIELDS =
      orderedSet("message", "assistantMetadata", "toolResultMetadata");
  private static final Set<String> MODEL_ATTEMPT_FAILURE_FIELDS =
      orderedSet("attempt", "model", "requestedAt", "error", "retryAt");
  private static final Set<String> NATIVE_COMPACTION_FIELDS =
      orderedSet(
          "trigger",
          "preTokens",
          "postTokens",
          "cumulativeDroppedTokens",
          "durationMs",
          "nativeHeadId",
          "nativeAnchorId",
          "nativeTailId");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS =
      orderedSet("contributorId", "customType", "rendererKey", "message", "details");
  private static final Set<String> ASSISTANT_ERROR_FIELDS = orderedSet("error", "attempt");
  private static final Set<String> ATTEMPT_SNAPSHOT_FIELDS =
      orderedSet("attempt", "sequence", "text", "thinking");
  private static final Set<String> ASSISTANT_ABORTED_FIELDS = orderedSet("message");
  private static final Set<String> COMPACTION_FIELDS =
      orderedSet("summaryText", "assistantMetadata");
  private static final Set<String> NOTIFICATION_FIELDS =
      orderedSet("notificationId", "kind", "sourceThreadId", "message");
  private static final Set<String> SETTINGS_FIELDS = orderedSet("settings", "ownerThreadId");
  private static final Set<String> FORK_FIELDS =
      orderedSet("mode", "sourceEntryId", "sourceThreadId");
  private static final Set<String> TURN_END_FIELDS =
      orderedSet("turnStartEntryId", "outcome", "continueModel", "reason", "closeRequestId");
  private static final Set<String> ERROR_FIELDS = orderedSet("code", "message");
  private static final Set<String> TOOL_RESULT_METADATA_FIELDS =
      orderedSet(
          "assistantEntryId",
          "toolCallId",
          "callIndex",
          "status",
          "synthetic",
          "reason",
          "inputReceipt",
          "invocationId",
          "toolName");

  private static final ProviderToolDefinitionJsonCodec PROVIDER_TOOL_DEFINITION_CODEC =
      new ProviderToolDefinitionJsonCodec();

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  public HistoryEntryPayloadJsonCodec() {}

  /** 把 {@link EntryPayload} 编码为 canonical JSON 文本；覆盖全部 sealed payload。 */
  public String encode(EntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeNode(payload));
  }

  /** 把 {@link EntryPayload} 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。 */
  public ObjectNode encodeNode(EntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return switch (payload) {
      case RootPayload value -> encodeRoot(value);
      case SystemPayload value -> encodeSystem(value);
      case TurnStartPayload value -> encodeTurnStart(value);
      case MessagePayload value -> encodeMessage(value);
      case ModelAttemptFailurePayload value -> encodeModelAttemptFailure(value);
      case NativeCompactionPayload value -> encodeNativeCompaction(value);
      case CustomMessagePayload value -> encodeCustomMessage(value);
      case AssistantErrorPayload value -> encodeAssistantError(value);
      case AssistantAbortedPayload value -> encodeAssistantAborted(value);
      case CompactionPayload value -> encodeCompaction(value);
      case TurnEndPayload value -> encodeTurnEnd(value);
      case NotificationPayload value -> encodeNotification(value);
      case SettingsPayload value -> encodeSettings(value);
      case ForkPayload value -> encodeFork(value);
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
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed " + type.name() + " entry payload JSON", error);
    }
    // Jackson 3 空白输入返回 MissingNode（Jackson 2 返回 null），两者都必须判为 malformed。
    if (root == null || root.isMissingNode()) {
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
      case SYSTEM -> decodeSystem(value);
      case TURN_START -> decodeTurnStart(value);
      case MESSAGE -> decodeMessage(value);
      case MODEL_ATTEMPT_FAILURE -> decodeModelAttemptFailure(value);
      case NATIVE_COMPACTION -> decodeNativeCompaction(value);
      case CUSTOM_MESSAGE -> decodeCustomMessage(value);
      case ASSISTANT_ERROR -> decodeAssistantError(value);
      case ASSISTANT_ABORTED -> decodeAssistantAborted(value);
      case COMPACTION -> decodeCompaction(value);
      case TURN_END -> decodeTurnEnd(value);
      case NOTIFICATION -> decodeNotification(value);
      case SETTINGS -> decodeSettings(value);
      case FORK -> decodeFork(value);
    };
  }

  // ---------- 编码器 ----------

  private static ObjectNode encodeRoot(RootPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("settings", HistoryValueCodecs.encodeBranchSettings(value.settings()));
    return node;
  }

  private static ObjectNode encodeSystem(SystemPayload value) {
    ObjectNode node = NODES.objectNode();
    ObjectNode sections = node.putObject("sections");
    value.sections().forEach(sections::put);
    ArrayNode removedSections = node.putArray("removedSections");
    value.removedSections().forEach(removedSections::add);
    ArrayNode added = node.putArray("addedTools");
    value
        .addedTools()
        .forEach(definition -> added.add(PROVIDER_TOOL_DEFINITION_CODEC.encodeNode(definition)));
    ArrayNode removed = node.putArray("removedTools");
    value.removedTools().forEach(removed::add);
    ArrayNode addedHosted = node.putArray("addedHostedTools");
    for (SystemHostedTool tool : value.addedHostedTools()) {
      ObjectNode item = addedHosted.addObject();
      item.put("name", tool.name());
      item.put("definitionJson", tool.definitionJson());
    }
    ArrayNode removedHosted = node.putArray("removedHostedTools");
    value.removedHostedTools().forEach(removedHosted::add);
    node.put("baseline", value.baseline());
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
    if (value.maxOutputTokens() == null) {
      node.putNull("maxOutputTokens");
    } else {
      node.put("maxOutputTokens", value.maxOutputTokens());
    }
    if (value.compaction() == null) {
      node.putNull("compaction");
    } else {
      node.set("compaction", encodeCompactionStart(value.compaction()));
    }
    if (value.executedSelection() == null) {
      node.putNull("executedSelection");
    } else {
      node.set("executedSelection", ExecutionSelectionJsonCodec.encode(value.executedSelection()));
    }
    if (value.capabilities() == null) {
      node.putNull("capabilities");
    } else {
      node.set("capabilities", encodeTurnCapabilities(value.capabilities()));
    }
    return node;
  }

  private static ObjectNode encodeTurnCapabilities(TurnCapabilities value) {
    ObjectNode node = NODES.objectNode();
    var subagents = node.putArray("subagentBindings");
    for (SubagentBinding binding : value.subagentBindings()) {
      ObjectNode item = subagents.addObject();
      item.put("name", binding.name());
      item.put("description", binding.description());
    }
    node.set("mediaCapabilities", encodeTurnMediaCapabilities(value.mediaCapabilities()));
    return node;
  }

  private static ObjectNode encodeTurnMediaCapabilities(TurnMediaCapabilities value) {
    ObjectNode node = NODES.objectNode();
    node.set("inputModalities", encodeModalities(value.inputModalities()));
    node.set("userModalities", encodeModalities(value.userModalities()));
    node.set("toolResultModalities", encodeModalities(value.toolResultModalities()));
    return node;
  }

  private static ArrayNode encodeModalities(Set<ModelInputModality> modalities) {
    ArrayNode array = NODES.arrayNode();
    modalities.stream().map(Enum::name).sorted().forEach(array::add);
    return array;
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

  private static ObjectNode encodeModelAttemptFailure(ModelAttemptFailurePayload value) {
    ObjectNode node = NODES.objectNode();
    ModelAttemptSnapshot attempt = value.attempt();
    ObjectNode attemptNode = NODES.objectNode();
    attemptNode.put("attempt", attempt.attempt());
    attemptNode.put("sequence", attempt.sequence());
    attemptNode.put("text", attempt.text());
    attemptNode.put("thinking", attempt.thinking());
    node.set("attempt", attemptNode);
    ObjectNode modelNode = NODES.objectNode();
    modelNode.put("modelName", value.model().modelName());
    if (value.model().variant() == null) {
      modelNode.putNull("variant");
    } else {
      modelNode.put("variant", value.model().variant());
    }
    node.set("model", modelNode);
    if (value.requestedAt() != null) {
      node.put("requestedAt", value.requestedAt().toString());
    } else {
      node.putNull("requestedAt");
    }
    node.set("error", encodeError(value.error()));
    node.put("retryAt", value.retryAt().toString());
    return node;
  }

  private static ObjectNode encodeNativeCompaction(NativeCompactionPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("trigger", value.trigger());
    node.put("preTokens", value.preTokens());
    node.put("postTokens", value.postTokens());
    node.put("cumulativeDroppedTokens", value.cumulativeDroppedTokens());
    node.put("durationMs", value.durationMs());
    node.put("nativeHeadId", value.nativeHeadId());
    node.put("nativeAnchorId", value.nativeAnchorId());
    node.put("nativeTailId", value.nativeTailId());
    return node;
  }

  private static ObjectNode encodeCustomMessage(CustomMessagePayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("contributorId", value.contributorId());
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
    node.put("summaryText", value.summaryText());
    if (value.assistantMetadata() == null) {
      node.putNull("assistantMetadata");
    } else {
      node.set(
          "assistantMetadata",
          HistoryValueCodecs.encodeAssistantMetadata(value.assistantMetadata()));
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

  private static ObjectNode encodeNotification(NotificationPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("notificationId", value.notificationId().toString());
    node.put("kind", value.kind().name());
    node.put("sourceThreadId", value.sourceThreadId().toString());
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    return node;
  }

  private static ObjectNode encodeSettings(SettingsPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("settings", HistoryValueCodecs.encodeBranchSettings(value.settings()));
    node.put("ownerThreadId", value.ownerThreadId().toString());
    return node;
  }

  private static ObjectNode encodeFork(ForkPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("mode", value.mode().name());
    node.put("sourceEntryId", value.sourceEntryId().toString());
    if (value.sourceThreadId() == null) {
      node.putNull("sourceThreadId");
    } else {
      node.put("sourceThreadId", value.sourceThreadId().toString());
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
    node.put("callIndex", metadata.callIndex());
    node.put("status", metadata.status().name());
    node.put("synthetic", metadata.synthetic());
    if (metadata.reason() == null) {
      node.putNull("reason");
    } else {
      node.put("reason", metadata.reason().name());
    }
    if (metadata.inputReceipt() == null) {
      node.putNull("inputReceipt");
    } else {
      node.set("inputReceipt", StrictJson.standard().tree(metadata.inputReceipt()));
    }
    if (metadata.invocationId() == null) {
      node.putNull("invocationId");
    } else {
      node.put("invocationId", metadata.invocationId().toString());
    }
    if (metadata.toolName() == null) {
      node.putNull("toolName");
    } else {
      node.put("toolName", metadata.toolName());
    }
    return node;
  }

  // ---------- 解码器 ----------

  private static RootPayload decodeRoot(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ROOT");
    JsonCodecSupport.requireFields(node, ROOT_FIELDS, "ROOT");
    return new RootPayload(
        HistoryValueCodecs.decodeBranchSettings(node.get("settings"), "ROOT.settings"));
  }

  private static SystemPayload decodeSystem(JsonNode value) {
    String context = "SYSTEM";
    ObjectNode node = HistoryValueCodecs.requireObject(value, context);
    JsonCodecSupport.requireFields(node, SYSTEM_FIELDS, context);
    ObjectNode sectionNode =
        HistoryValueCodecs.requireObject(node.get("sections"), context + ".sections");
    Map<String, String> sections = new LinkedHashMap<>();
    sectionNode
        .properties()
        .forEach(
            entry -> {
              if (!entry.getValue().isString() || entry.getValue().stringValue().isBlank()) {
                throw new IllegalArgumentException("SYSTEM.sections values must be non-blank text");
              }
              sections.put(entry.getKey(), entry.getValue().stringValue());
            });
    ArrayNode removedSectionValues =
        HistoryValueCodecs.requireArray(node.get("removedSections"), context + ".removedSections");
    List<String> removedSections = new ArrayList<>(removedSectionValues.size());
    for (JsonNode item : removedSectionValues) {
      if (!item.isString()) {
        throw new IllegalArgumentException("SYSTEM.removedSections[] must be text");
      }
      removedSections.add(item.stringValue());
    }
    ArrayNode added =
        HistoryValueCodecs.requireArray(node.get("addedTools"), context + ".addedTools");
    List<ProviderToolDefinition> addedTools = new ArrayList<>(added.size());
    for (JsonNode item : added) {
      addedTools.add(PROVIDER_TOOL_DEFINITION_CODEC.decodeNode(item));
    }
    ArrayNode removed =
        HistoryValueCodecs.requireArray(node.get("removedTools"), context + ".removedTools");
    List<String> removedTools = new ArrayList<>(removed.size());
    for (JsonNode item : removed) {
      if (!item.isString()) {
        throw new IllegalArgumentException("SYSTEM.removedTools[] must be text");
      }
      removedTools.add(item.stringValue());
    }
    ArrayNode addedHostedValues =
        HistoryValueCodecs.requireArray(
            node.get("addedHostedTools"), context + ".addedHostedTools");
    List<SystemHostedTool> addedHostedTools = new ArrayList<>(addedHostedValues.size());
    for (JsonNode item : addedHostedValues) {
      ObjectNode hostedTool =
          HistoryValueCodecs.requireObject(item, context + ".addedHostedTools[]");
      JsonCodecSupport.requireFields(
          hostedTool, SYSTEM_HOSTED_TOOL_FIELDS, context + ".addedHostedTools[]");
      addedHostedTools.add(
          new SystemHostedTool(
              HistoryValueCodecs.requiredText(hostedTool, "name", context + ".addedHostedTools[]"),
              HistoryValueCodecs.requiredText(
                  hostedTool, "definitionJson", context + ".addedHostedTools[]")));
    }
    ArrayNode removedHostedValues =
        HistoryValueCodecs.requireArray(
            node.get("removedHostedTools"), context + ".removedHostedTools");
    List<String> removedHostedTools = new ArrayList<>(removedHostedValues.size());
    for (JsonNode item : removedHostedValues) {
      if (!item.isString()) {
        throw new IllegalArgumentException("SYSTEM.removedHostedTools[] must be text");
      }
      removedHostedTools.add(item.stringValue());
    }
    if (!node.get("baseline").isBoolean()) {
      throw new IllegalArgumentException("SYSTEM.baseline must be boolean");
    }
    return new SystemPayload(
        sections,
        removedSections,
        addedTools,
        removedTools,
        addedHostedTools,
        removedHostedTools,
        node.get("baseline").booleanValue());
  }

  private static TurnStartPayload decodeTurnStart(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "TURN_START");
    JsonCodecSupport.requireFields(node, TURN_START_FIELDS, "TURN_START");
    return new TurnStartPayload(
        HistoryValueCodecs.readEnum(
            TurnStartReason.class, HistoryValueCodecs.text(node, "reason"), "TURN_START.reason"),
        HistoryValueCodecs.decodeBranchSettings(node.get("settings"), "TURN_START.settings"),
        HistoryValueCodecs.requiredPositiveId(node, "ownerThreadId", "TURN_START"),
        HistoryValueCodecs.nullablePositiveInt(node, "contextWindow", "TURN_START"),
        HistoryValueCodecs.nullablePositiveInt(node, "maxOutputTokens", "TURN_START"),
        decodeNullableCompactionStart(node.get("compaction")),
        node.get("executedSelection").isNull()
            ? null
            : ExecutionSelectionJsonCodec.decode(
                node.get("executedSelection"), "TURN_START.executedSelection"),
        node.get("capabilities").isNull()
            ? null
            : decodeTurnCapabilities(node.get("capabilities")));
  }

  private static TurnCapabilities decodeTurnCapabilities(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "TURN_START.capabilities");
    JsonCodecSupport.requireFields(node, TURN_CAPABILITIES_FIELDS, "TURN_START.capabilities");
    ArrayNode subagentValues =
        HistoryValueCodecs.requireArray(
            node.get("subagentBindings"), "TURN_START.capabilities.subagentBindings");
    List<SubagentBinding> subagentBindings = new ArrayList<>(subagentValues.size());
    for (JsonNode subagentValue : subagentValues) {
      String context = "TURN_START.capabilities.subagentBindings[]";
      ObjectNode subagent = HistoryValueCodecs.requireObject(subagentValue, context);
      JsonCodecSupport.requireFields(subagent, SUBAGENT_BINDING_FIELDS, context);
      subagentBindings.add(
          new SubagentBinding(
              HistoryValueCodecs.requiredText(subagent, "name", context),
              HistoryValueCodecs.requiredText(subagent, "description", context)));
    }
    return new TurnCapabilities(
        subagentBindings, decodeTurnMediaCapabilities(node.get("mediaCapabilities")));
  }

  private static TurnMediaCapabilities decodeTurnMediaCapabilities(JsonNode value) {
    String context = "TURN_START.capabilities.mediaCapabilities";
    ObjectNode node = HistoryValueCodecs.requireObject(value, context);
    JsonCodecSupport.requireFields(node, TURN_MEDIA_CAPABILITIES_FIELDS, context);
    return new TurnMediaCapabilities(
        decodeModalities(node.get("inputModalities"), context + ".inputModalities"),
        decodeModalities(node.get("userModalities"), context + ".userModalities"),
        decodeModalities(node.get("toolResultModalities"), context + ".toolResultModalities"));
  }

  private static Set<ModelInputModality> decodeModalities(JsonNode value, String context) {
    ArrayNode array = HistoryValueCodecs.requireArray(value, context);
    Set<ModelInputModality> modalities = new LinkedHashSet<>();
    for (JsonNode item : array) {
      if (!item.isString()) {
        throw new IllegalArgumentException(context + "[] must be text");
      }
      ModelInputModality modality;
      try {
        modality = ModelInputModality.valueOf(item.stringValue());
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException(context + "[] is not a known modality", error);
      }
      if (!modalities.add(modality)) {
        throw new IllegalArgumentException(context + "[] must not repeat");
      }
    }
    return Set.copyOf(modalities);
  }

  private static MessagePayload decodeMessage(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "MESSAGE");
    JsonCodecSupport.requireFields(node, MESSAGE_FIELDS, "MESSAGE");
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

  private static CustomMessagePayload decodeCustomMessage(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "CUSTOM_MESSAGE");
    JsonCodecSupport.requireFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessagePayload(
        CanonicalIdentifiers.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "contributorId"), "contributorId"),
        CanonicalIdentifiers.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "customType"), "customType"),
        CanonicalIdentifiers.requireCanonicalIdentifier(
            HistoryValueCodecs.text(node, "rendererKey"), "rendererKey"),
        MESSAGE_CODEC.decodeNode(node.get("message")),
        JsonObjects.write(
            HistoryValueCodecs.requireObject(node.get("details"), "CUSTOM_MESSAGE.details")));
  }

  private static ModelAttemptFailurePayload decodeModelAttemptFailure(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "MODEL_ATTEMPT_FAILURE");
    JsonCodecSupport.requireFields(node, MODEL_ATTEMPT_FAILURE_FIELDS, "MODEL_ATTEMPT_FAILURE");
    ObjectNode attemptNode =
        HistoryValueCodecs.requireObject(node.get("attempt"), "MODEL_ATTEMPT_FAILURE.attempt");
    JsonCodecSupport.requireFields(
        attemptNode, ATTEMPT_SNAPSHOT_FIELDS, "MODEL_ATTEMPT_FAILURE.attempt");
    ModelAttemptSnapshot attempt =
        new ModelAttemptSnapshot(
            HistoryValueCodecs.requiredPositiveInt(
                attemptNode, "attempt", "MODEL_ATTEMPT_FAILURE.attempt"),
            HistoryValueCodecs.requiredNonNegativeLong(
                attemptNode, "sequence", "MODEL_ATTEMPT_FAILURE.attempt"),
            HistoryValueCodecs.text(attemptNode, "text"),
            HistoryValueCodecs.text(attemptNode, "thinking"));
    ObjectNode modelNode =
        HistoryValueCodecs.requireObject(node.get("model"), "MODEL_ATTEMPT_FAILURE.model");
    JsonCodecSupport.requireFields(
        modelNode, orderedSet("modelName", "variant"), "MODEL_ATTEMPT_FAILURE.model");
    ModelSelection model =
        ModelSelection.model(
            HistoryValueCodecs.text(modelNode, "modelName"),
            modelNode.get("variant").isNull()
                ? null
                : HistoryValueCodecs.text(modelNode, "variant"));
    Instant requestedAt =
        node.get("requestedAt").isNull()
            ? null
            : parseInstant(node, "requestedAt", "MODEL_ATTEMPT_FAILURE.requestedAt");
    Instant retryAt = parseInstant(node, "retryAt", "MODEL_ATTEMPT_FAILURE.retryAt");
    return new ModelAttemptFailurePayload(
        attempt, model, requestedAt, decodeError(node.get("error")), retryAt);
  }

  private static NativeCompactionPayload decodeNativeCompaction(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "NATIVE_COMPACTION");
    JsonCodecSupport.requireFields(node, NATIVE_COMPACTION_FIELDS, "NATIVE_COMPACTION");
    return new NativeCompactionPayload(
        nativeText(node, "trigger"),
        nativeCount(node, "preTokens"),
        nativeCount(node, "postTokens"),
        nativeCount(node, "cumulativeDroppedTokens"),
        nativeCount(node, "durationMs"),
        nativeText(node, "nativeHeadId"),
        nativeText(node, "nativeAnchorId"),
        nativeText(node, "nativeTailId"));
  }

  private static String nativeText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("invalid native compaction text: " + field);
    }
    return value.asText();
  }

  private static Long nativeCount(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("invalid native compaction count: " + field);
    }
    return value.longValue();
  }

  private static Instant parseInstant(ObjectNode node, String field, String context) {
    try {
      return Instant.parse(HistoryValueCodecs.text(node, field));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(context + " must be an ISO-8601 instant", error);
    }
  }

  private static AssistantErrorPayload decodeAssistantError(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ASSISTANT_ERROR");
    JsonCodecSupport.requireFields(node, ASSISTANT_ERROR_FIELDS, "ASSISTANT_ERROR");
    JsonNode attempt = node.get("attempt");
    ModelAttemptSnapshot snapshot = null;
    if (!attempt.isNull()) {
      ObjectNode snapshotNode =
          HistoryValueCodecs.requireObject(attempt, "ASSISTANT_ERROR.attempt");
      JsonCodecSupport.requireFields(
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
    JsonCodecSupport.requireFields(node, ASSISTANT_ABORTED_FIELDS, "ASSISTANT_ABORTED");
    return new AssistantAbortedPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static CompactionPayload decodeCompaction(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "COMPACTION");
    JsonCodecSupport.requireFields(node, COMPACTION_FIELDS, "COMPACTION");
    JsonNode assistantMetadata = node.get("assistantMetadata");
    AssistantMessageMetadata metadata =
        assistantMetadata.isNull()
            ? null
            : HistoryValueCodecs.decodeAssistantMetadata(assistantMetadata);
    return new CompactionPayload(HistoryValueCodecs.text(node, "summaryText"), metadata);
  }

  private static final Set<String> COMPACTION_START_FIELDS =
      orderedSet(
          "trigger",
          "execution",
          "outputBudget",
          "cutEntryId",
          "childThreadId",
          "joinInvocationId");

  private static ObjectNode encodeCompactionStart(CompactionStart start) {
    ObjectNode node = NODES.objectNode();
    node.put("trigger", start.trigger().name());
    if (start.execution() == null) {
      node.putNull("execution");
    } else {
      node.set("execution", ExecutionSelectionJsonCodec.encode(start.execution()));
    }
    if (start.outputBudget() == null) {
      node.putNull("outputBudget");
    } else {
      node.put("outputBudget", start.outputBudget());
    }
    node.put("cutEntryId", start.cutEntryId().toString());
    if (start.childThreadId() == null) {
      node.putNull("childThreadId");
    } else {
      node.put("childThreadId", start.childThreadId().toString());
    }
    if (start.joinInvocationId() == null) {
      node.putNull("joinInvocationId");
    } else {
      node.put("joinInvocationId", start.joinInvocationId().toString());
    }
    return node;
  }

  private static CompactionStart decodeNullableCompactionStart(JsonNode value) {
    if (value.isNull()) {
      return null;
    }
    ObjectNode node = HistoryValueCodecs.requireObject(value, "TURN_START.compaction");
    JsonCodecSupport.requireFields(node, COMPACTION_START_FIELDS, "TURN_START.compaction");
    JsonNode executionNode = node.get("execution");
    ExecutionSelection execution =
        executionNode == null || executionNode.isNull()
            ? null
            : ExecutionSelectionJsonCodec.decode(executionNode, "TURN_START.compaction.execution");
    return new CompactionStart(
        HistoryValueCodecs.readEnum(
            CompactionTrigger.class,
            HistoryValueCodecs.text(node, "trigger"),
            "TURN_START.compaction.trigger"),
        execution,
        HistoryValueCodecs.nullablePositiveLong(node, "outputBudget", "TURN_START.compaction"),
        HistoryValueCodecs.requiredPositiveId(node, "cutEntryId", "TURN_START.compaction"),
        HistoryValueCodecs.nullablePositiveId(node, "childThreadId", "TURN_START.compaction"),
        HistoryValueCodecs.nullablePositiveId(node, "joinInvocationId", "TURN_START.compaction"));
  }

  private static TurnEndPayload decodeTurnEnd(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "TURN_END");
    JsonCodecSupport.requireFields(node, TURN_END_FIELDS, "TURN_END");
    return new TurnEndPayload(
        HistoryValueCodecs.requiredPositiveId(node, "turnStartEntryId", "TURN_END"),
        HistoryValueCodecs.readEnum(
            TurnEndOutcome.class, HistoryValueCodecs.text(node, "outcome"), "TURN_END.outcome"),
        HistoryValueCodecs.requiredBoolean(node, "continueModel", "TURN_END"),
        HistoryValueCodecs.nullableEnum(TurnEndReason.class, node, "reason", "TURN_END.reason"),
        HistoryValueCodecs.nullablePositiveId(node, "closeRequestId", "TURN_END"));
  }

  private static NotificationPayload decodeNotification(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "NOTIFICATION");
    JsonCodecSupport.requireFields(node, NOTIFICATION_FIELDS, "NOTIFICATION");
    return new NotificationPayload(
        HistoryValueCodecs.requiredPositiveId(node, "notificationId", "NOTIFICATION"),
        HistoryValueCodecs.readEnum(
            NotificationKind.class, HistoryValueCodecs.text(node, "kind"), "NOTIFICATION.kind"),
        HistoryValueCodecs.requiredPositiveId(node, "sourceThreadId", "NOTIFICATION"),
        MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static SettingsPayload decodeSettings(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "SETTINGS");
    JsonCodecSupport.requireFields(node, SETTINGS_FIELDS, "SETTINGS");
    return new SettingsPayload(
        HistoryValueCodecs.decodeBranchSettings(node.get("settings"), "SETTINGS.settings"),
        HistoryValueCodecs.requiredPositiveId(node, "ownerThreadId", "SETTINGS"));
  }

  private static ForkPayload decodeFork(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "FORK");
    JsonCodecSupport.requireFields(node, FORK_FIELDS, "FORK");
    return new ForkPayload(
        HistoryValueCodecs.readEnum(
            ForkMode.class, HistoryValueCodecs.text(node, "mode"), "FORK.mode"),
        HistoryValueCodecs.requiredPositiveId(node, "sourceEntryId", "FORK"),
        HistoryValueCodecs.nullablePositiveId(node, "sourceThreadId", "FORK"));
  }

  private static AssistantError decodeError(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "ASSISTANT_ERROR.error");
    JsonCodecSupport.requireFields(node, ERROR_FIELDS, "ASSISTANT_ERROR.error");
    return new AssistantError(
        HistoryValueCodecs.text(node, "code"), HistoryValueCodecs.text(node, "message"));
  }

  private static ToolResultMetadata decodeToolResultMetadata(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, "toolResultMetadata");
    JsonCodecSupport.requireFields(node, TOOL_RESULT_METADATA_FIELDS, "toolResultMetadata");
    return new ToolResultMetadata(
        HistoryValueCodecs.nullablePositiveId(node, "invocationId", "toolResultMetadata"),
        HistoryValueCodecs.requiredPositiveId(node, "assistantEntryId", "toolResultMetadata"),
        HistoryValueCodecs.text(node, "toolCallId"),
        HistoryValueCodecs.requiredNonNegativeInt(node, "callIndex", "toolResultMetadata"),
        HistoryValueCodecs.readEnum(
            ToolResultStatus.class,
            HistoryValueCodecs.text(node, "status"),
            "toolResultMetadata.status"),
        HistoryValueCodecs.requiredBoolean(node, "synthetic", "toolResultMetadata"),
        HistoryValueCodecs.nullableEnum(
            ToolResultReason.class, node, "reason", "toolResultMetadata.reason"),
        node.get("inputReceipt") == null || node.get("inputReceipt").isNull()
            ? null
            : StrictJson.standard()
                .read(node.get("inputReceipt"), ToolInputReceipt.class, "inputReceipt"),
        HistoryValueCodecs.nullableText(node, "toolName"));
  }

  // ---------- 通用工具方法 ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode history entry payload JSON", error);
    }
  }

  private static Set<String> orderedSet(String... values) {
    return HistoryValueCodecs.orderedSet(values);
  }
}
