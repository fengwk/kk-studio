package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * OpenAI Responses SSE 流式事件状态机与响应累积器。
 *
 * <p>按协议规范解析文本增量、推理增量与工具调用增量，处理 completed/incomplete/failed 事件， 进行细粒度用量归一化与 native replay 组装：已知
 * message/reasoning/function_call 归一化，其余官方 output item 作为不透明事实保留。
 *
 * <p>每条 transport 帧在进入状态机之前先经 {@link OpenAiResponsesStreamBridge#emitProtocolEvent} exactly-once
 * 上报原生事实；原生帧不进入 durable checkpoint、realtime 增量或任何内容缓冲。
 */
final class OpenAiResponsesStreamAccumulator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> KNOWN_SSE_EVENTS =
      Set.of(
          "response.created",
          "response.output_text.delta",
          "response.refusal.delta",
          "response.refusal.done",
          "response.reasoning_text.delta",
          "response.reasoning_summary_text.delta",
          "response.output_item.added",
          "response.function_call_arguments.delta",
          "response.function_call_arguments.done",
          "response.output_item.done",
          "response.completed",
          "response.incomplete",
          "response.failed",
          "response.error",
          "error");

  /** 无法从 transport 帧还原出官方事件名时使用的兜底原生事件名。 */
  private static final String UNKNOWN_PROTOCOL_EVENT_TYPE = "openai.response.event";

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final String frozenSourcePrefixHash;
  private final OpenAiResponsesStreamBridge bridge;

  private boolean terminalReceived = false;
  private String requestId = null;
  private String serviceTier = null;
  private GenerationStopReason stopReason = null;

  private final StringBuilder textBuffer = new StringBuilder();
  private final StringBuilder thinkingBuffer = new StringBuilder();

  // 记录流中维护的工具调用构建状态；ordinal 只在首次观察到 function_call 时分配并保持稳定
  private final Map<String, WireToolCall> toolsById = new LinkedHashMap<>();
  private int nextToolOrdinal = 0;

  // 终态回放用有序 output items
  private final List<JsonNode> rawOutputItems = new ArrayList<>();
  // 未知官方 item 在 rawOutputItems 中的槽位：added 先占位，done 覆盖同一槽位，避免重复或丢失
  private final Map<String, Integer> opaqueItemSlots = new LinkedHashMap<>();
  private boolean explicitTerminalOutputProcessed = false;

  // Token 用量
  private JsonNode latestNativeUsageNode = null;
  private long rawInputTokens = 0L;
  private long rawOutputTokens = 0L;
  private long rawTotalTokens = 0L;
  private boolean hasRawTotalTokens = false;
  private long cachedTokens = 0L;
  private long cacheWriteTokens = 0L;
  private long reasoningTokens = 0L;
  private ProviderCompletion finishedCompletion = null;

  /**
   * 流中维护的工具调用构建状态。
   *
   * <p>{@code ordinal} 是工具调用之间的连续序号（0..N-1），用于 {@link ProviderStreamEvent.ToolCallDelta}；它必须区别于
   * Responses 协议的 output_index——后者同时计入 reasoning / message 等非工具 item，直接透出会造成序号空洞。
   */
  private static final class WireToolCall {
    final int ordinal;
    String id;
    String callId;
    String name;
    final StringBuilder argumentsBuffer = new StringBuilder();
    boolean argumentsPresent = false;
    boolean argumentsTextual = true;
    boolean argumentsDone = false;
    boolean itemDone = false;

    WireToolCall(int ordinal, String id, String callId, String name) {
      this.ordinal = ordinal;
      this.id = id;
      this.callId = callId;
      this.name = name;
    }
  }

  OpenAiResponsesStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      String frozenSourcePrefixHash,
      OpenAiResponsesStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.frozenSourcePrefixHash =
        Objects.requireNonNull(frozenSourcePrefixHash, "frozenSourcePrefixHash");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
  }

  OpenAiResponsesStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      String frozenSourcePrefixHash,
      Consumer<ProviderStreamEvent> eventConsumer) {
    this(request, descriptor, frozenSourcePrefixHash, createBridge(eventConsumer));
  }

  private static OpenAiResponsesStreamBridge createBridge(
      Consumer<ProviderStreamEvent> eventConsumer) {
    return new OpenAiResponsesStreamBridge(
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            if (eventConsumer != null) {
              eventConsumer.accept(event);
            }
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        });
  }

  void handleEvent(String eventName, String data) {
    if (bridge.isCancelled()) {
      return;
    }
    // 每条 transport 帧（含 keepalive ping、[DONE] 哨兵与畸形帧）先 exactly-once 上报原生事实，
    // 再进入规范化状态机；failed/error 因此必然是「先 raw 再异常」。
    bridge.emitProtocolEvent(protocolEvent(eventName, data));
    if ("ping".equals(eventName)) {
      return;
    }
    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
      return;
    }

    JsonNode node = parseJson(data);
    if (!node.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE event data is not a JSON object");
    }

    processEvent(node);
  }

  /**
   * 还原 transport 帧的原生身份：优先 SSE {@code event} 名，其次 payload 的 JSON {@code type}，最后是通用兜底名； {@code
   * [DONE]} 哨兵归一为 {@code done}。只有帧身份被解释，{@code data} 始终原样透出，绝不重写厂商事实。
   */
  private static ProviderProtocolEvent protocolEvent(String eventName, String data) {
    String payload = data == null ? "" : data;
    if (eventName != null && !eventName.isBlank()) {
      return new ProviderProtocolEvent(eventName, payload);
    }
    if ("[DONE]".equals(payload.trim())) {
      return new ProviderProtocolEvent("done", payload);
    }
    JsonNode node = tryParseJsonObject(payload);
    if (node != null) {
      JsonNode typeNode = node.get("type");
      if (typeNode != null && typeNode.isTextual() && !typeNode.textValue().isBlank()) {
        return new ProviderProtocolEvent(typeNode.textValue(), payload);
      }
    }
    return new ProviderProtocolEvent(UNKNOWN_PROTOCOL_EVENT_TYPE, payload);
  }

  void processEvent(JsonNode node) {
    if (node == null || !node.isObject()) {
      return;
    }
    String type = node.path("type").asText();

    if ("response.failed".equals(type) || "response.error".equals(type) || "error".equals(type)) {
      throw OpenAiResponsesErrorMapper.mapSseErrorEnvelope(node);
    }

    if (!KNOWN_SSE_EVENTS.contains(type)) {
      return;
    }

    switch (type) {
      case "response.created" -> handleCreated(node);
      case "response.output_text.delta", "response.refusal.delta" -> handleTextDelta(node);
      case "response.refusal.done" -> {
        // delta 与 terminal output 已分别承担实时展示和权威终态，done 仅表示该 content part 完成。
      }
      case "response.reasoning_text.delta",
          "response.reasoning_summary_text.delta" -> handleThinkingDelta(node);
      case "response.output_item.added" -> handleOutputItemAdded(node);
      case "response.function_call_arguments.delta" -> handleArgumentsDelta(node);
      case "response.function_call_arguments.done" -> handleArgumentsDone(node);
      case "response.output_item.done" -> handleOutputItemDone(node);
      case "response.completed" -> handleCompleted(node);
      case "response.incomplete" -> handleIncomplete(node);
      default -> {}
    }
  }

  private void handleCreated(JsonNode node) {
    JsonNode respNode = node.get("response");
    if (respNode != null && respNode.isObject()) {
      extractResponseData(respNode, false);
    }
  }

  private void handleTextDelta(JsonNode node) {
    String delta = node.path("delta").asText("");
    if (!delta.isEmpty()) {
      textBuffer.append(delta);
      bridge.emitEvent(new ProviderStreamEvent.TextDelta(delta));
    }
  }

  private void handleThinkingDelta(JsonNode node) {
    String delta = node.path("delta").asText("");
    if (!delta.isEmpty()) {
      thinkingBuffer.append(delta);
      bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(delta));
    }
  }

  private void handleOutputItemAdded(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject()) {
      return;
    }
    String itemType = item.path("type").asText();
    if ("function_call".equals(itemType)) {
      String id = item.path("id").asText(null);
      if (id == null || id.isBlank()) {
        return;
      }
      String callId = item.path("call_id").asText(null);
      if (callId == null || callId.isBlank()) {
        callId = id;
      }
      String name = item.path("name").asText(null);
      // 首次观察到的 function_call 分配下一个连续 ordinal；重复 added 复用既有 ordinal 以避免序号空洞
      WireToolCall previous = toolsById.get(id);
      int ordinal = previous != null ? previous.ordinal : nextToolOrdinal++;
      toolsById.put(id, new WireToolCall(ordinal, id, callId, name));
      if (callId != null || name != null) {
        bridge.emitEvent(new ProviderStreamEvent.ToolCallDelta(ordinal, callId, name, null));
      }
      return;
    }
    // 已知 message/reasoning 的权威表示来自 delta、done 与 terminal output；未知官方 item 只有这条流式事实来源。
    if (!isKnownOutputItemType(itemType)) {
      retainStreamedOpaqueItem(node);
    }
  }

  private void handleArgumentsDelta(JsonNode node) {
    String itemId = node.path("item_id").asText(null);
    String delta = node.path("delta").asText("");
    if (itemId != null && toolsById.containsKey(itemId)) {
      WireToolCall tool = toolsById.get(itemId);
      tool.argumentsBuffer.append(delta);
      tool.argumentsPresent = true;
      tool.argumentsTextual = true;
      if (!delta.isEmpty()) {
        bridge.emitEvent(new ProviderStreamEvent.ToolCallDelta(tool.ordinal, null, null, delta));
      }
    }
  }

  private void handleArgumentsDone(JsonNode node) {
    String itemId = node.path("item_id").asText(null);
    String arguments = node.path("arguments").asText(null);
    if (itemId != null && toolsById.containsKey(itemId)) {
      WireToolCall tool = toolsById.get(itemId);
      if (arguments != null) {
        tool.argumentsBuffer.setLength(0);
        tool.argumentsBuffer.append(arguments);
        tool.argumentsPresent = true;
        tool.argumentsTextual = true;
      }
      tool.argumentsDone = true;
    }
  }

  private WireToolCall findToolCall(String itemId, String callId) {
    if (itemId != null && !itemId.isBlank()) {
      WireToolCall byId = toolsById.get(itemId);
      if (byId != null) {
        return byId;
      }
    }
    if (callId != null && !callId.isBlank()) {
      WireToolCall byCallId = toolsById.get(callId);
      if (byCallId != null) {
        return byCallId;
      }
    }
    for (WireToolCall t : toolsById.values()) {
      if ((itemId != null && !itemId.isBlank() && (itemId.equals(t.id) || itemId.equals(t.callId)))
          || (callId != null
              && !callId.isBlank()
              && (callId.equals(t.callId) || callId.equals(t.id)))) {
        return t;
      }
    }
    return null;
  }

  private static void retainStreamedReasoningEncryptedContent(
      ObjectNode terminalItem, List<JsonNode> streamedItems) {
    if (terminalItem.has("encrypted_content")) {
      return;
    }

    JsonNode terminalId = terminalItem.get("id");
    if (terminalId == null || !terminalId.isTextual() || terminalId.textValue().isBlank()) {
      return;
    }

    for (JsonNode streamedItem : streamedItems) {
      if (!streamedItem.isObject()
          || !"reasoning".equals(streamedItem.path("type").asText())
          || !terminalId.textValue().equals(streamedItem.path("id").asText(null))) {
        continue;
      }
      JsonNode encryptedContent = streamedItem.get("encrypted_content");
      if (encryptedContent != null
          && encryptedContent.isTextual()
          && !encryptedContent.textValue().isBlank()) {
        terminalItem.set("encrypted_content", encryptedContent.deepCopy());
      }
      return;
    }
  }

  private void syncToolFromItem(JsonNode item) {
    String itemId = item.path("id").asText(null);
    String callId = item.path("call_id").asText(null);
    WireToolCall tool = findToolCall(itemId, callId);
    if (tool == null) {
      // 未在流中登记过的 function_call（例如只出现在 output_item.done 或 terminal output）在此分配连续 ordinal
      int ordinal = nextToolOrdinal++;
      String key =
          (itemId != null && !itemId.isBlank())
              ? itemId
              : ((callId != null && !callId.isBlank()) ? callId : ("tool_" + ordinal));
      tool = new WireToolCall(ordinal, itemId, callId, null);
      toolsById.put(key, tool);
    }
    if (itemId != null && !itemId.isBlank()) {
      tool.id = itemId;
    }
    if (callId != null && !callId.isBlank()) {
      tool.callId = callId;
    }
    if (item.has("name")) {
      tool.name = item.path("name").asText(null);
    }
    if (item.has("arguments")) {
      if (item.get("arguments") != null && !item.get("arguments").isNull()) {
        tool.argumentsPresent = true;
        tool.argumentsTextual = item.get("arguments").isTextual();
        if (tool.argumentsTextual) {
          tool.argumentsBuffer.setLength(0);
          tool.argumentsBuffer.append(item.get("arguments").asText());
        }
        tool.argumentsDone = true;
      } else {
        tool.argumentsPresent = false;
        tool.argumentsDone = false;
      }
    } else {
      if (tool.argumentsBuffer.length() == 0) {
        tool.argumentsPresent = false;
        tool.argumentsDone = false;
      }
    }
    tool.itemDone = true;
  }

  /**
   * 记录流式到达的未知官方 item。
   *
   * <p>{@code output_item.added} 先建立槽位：未知 item 没有 delta 等其它语义来源，丢掉就再也回不来；随后到达的 {@code
   * output_item.done} 以更完整的 deepCopy 覆盖同一槽位，成为该 item 的 replay 事实。槽位键优先取 added/done 共享的 {@code
   * output_index}，其次取 item id；两者都缺失的 done 帧仍然追加，绝不静默丢弃。
   */
  private void retainStreamedOpaqueItem(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject() || !isOpaqueReplayItem(item)) {
      return;
    }
    String key = opaqueItemSlotKey(node, item);
    Integer slot = key == null ? null : opaqueItemSlots.get(key);
    if (slot != null) {
      rawOutputItems.set(slot, item.deepCopy());
      return;
    }
    if (key != null) {
      opaqueItemSlots.put(key, rawOutputItems.size());
    }
    rawOutputItems.add(item.deepCopy());
  }

  private static String opaqueItemSlotKey(JsonNode node, JsonNode item) {
    JsonNode outputIndex = node.get("output_index");
    if (outputIndex != null && outputIndex.isIntegralNumber()) {
      return "index:" + outputIndex.asInt();
    }
    JsonNode id = item.get("id");
    if (id != null && id.isTextual() && !id.textValue().isBlank()) {
      return "id:" + id.textValue();
    }
    return null;
  }

  private void handleOutputItemDone(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject()) {
      return;
    }
    String itemType = item.path("type").asText();
    if (!isKnownOutputItemType(itemType)) {
      retainStreamedOpaqueItem(node);
      return;
    }
    if ("function_call".equals(itemType)) {
      syncToolFromItem(item);
    }
    rawOutputItems.add(item.deepCopy());
  }

  private void handleCompleted(JsonNode node) {
    terminalReceived = true;
    stopReason = GenerationStopReason.COMPLETE;
    extractResponseData(node.get("response"), true);
  }

  private void handleIncomplete(JsonNode node) {
    terminalReceived = true;
    JsonNode responseNode = node.get("response");
    String reason = "";
    if (responseNode != null && responseNode.has("incomplete_details")) {
      reason = responseNode.path("incomplete_details").path("reason").asText("");
    }
    if ("content_filter".equals(reason)) {
      stopReason = GenerationStopReason.FILTERED;
    } else {
      stopReason = GenerationStopReason.LENGTH;
    }
    extractResponseData(responseNode, true);
  }

  private static long parseNonNegativeLong(JsonNode node, String fieldName) {
    if (node == null || node.isNull()) {
      return 0L;
    }
    if (!node.isNumber() || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "invalid usage token count in field "
              + fieldName
              + ": must be an integral number within long range");
    }
    long val = node.asLong();
    if (val < 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "invalid usage token count in field " + fieldName + ": must be non-negative");
    }
    return val;
  }

  private void resetUsage() {
    latestNativeUsageNode = null;
    rawInputTokens = 0L;
    rawOutputTokens = 0L;
    hasRawTotalTokens = false;
    rawTotalTokens = 0L;
    cachedTokens = 0L;
    cacheWriteTokens = 0L;
    reasoningTokens = 0L;
  }

  private void extractResponseData(JsonNode responseNode, boolean isTerminal) {
    if (responseNode == null || !responseNode.isObject()) {
      return;
    }
    if (responseNode.has("id") && !responseNode.path("id").asText().isBlank()) {
      requestId = responseNode.path("id").asText();
    }
    if (responseNode.has("service_tier") && !responseNode.path("service_tier").asText().isBlank()) {
      serviceTier = responseNode.path("service_tier").asText();
    }

    if (responseNode.has("usage")) {
      JsonNode usageNode = responseNode.get("usage");
      if (usageNode == null || usageNode.isNull()) {
        resetUsage();
      } else {
        if (!usageNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "usage is not a JSON object");
        }
        resetUsage();
        latestNativeUsageNode = usageNode.deepCopy();

        rawInputTokens = parseNonNegativeLong(usageNode.get("input_tokens"), "input_tokens");
        rawOutputTokens = parseNonNegativeLong(usageNode.get("output_tokens"), "output_tokens");

        JsonNode totalNode = usageNode.get("total_tokens");
        if (totalNode != null && !totalNode.isNull()) {
          hasRawTotalTokens = true;
          rawTotalTokens = parseNonNegativeLong(totalNode, "total_tokens");
        }

        JsonNode inDetails = usageNode.get("input_tokens_details");
        if (inDetails != null && !inDetails.isNull()) {
          if (!inDetails.isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "input_tokens_details is not a JSON object");
          }
          cachedTokens = parseNonNegativeLong(inDetails.get("cached_tokens"), "cached_tokens");
          cacheWriteTokens =
              parseNonNegativeLong(inDetails.get("cache_write_tokens"), "cache_write_tokens");
        }

        JsonNode outDetails = usageNode.get("output_tokens_details");
        if (outDetails != null && !outDetails.isNull()) {
          if (!outDetails.isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "output_tokens_details is not a JSON object");
          }
          reasoningTokens =
              parseNonNegativeLong(outDetails.get("reasoning_tokens"), "reasoning_tokens");
        }
      }
    }

    if (responseNode.has("output")) {
      JsonNode outputNode = responseNode.get("output");
      if (outputNode == null || outputNode.isNull() || !outputNode.isArray()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "response output must be a JSON array");
      }
      if (isTerminal) {
        explicitTerminalOutputProcessed = true;
        List<JsonNode> priorStreamedItems = new ArrayList<>(rawOutputItems);
        String priorStreamedThinking = thinkingBuffer.toString();
        rawOutputItems.clear();
        opaqueItemSlots.clear();
        toolsById.clear();
        nextToolOrdinal = 0;
        textBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        boolean terminalReasoningItemSeen = false;
        boolean terminalUsableSummarySeen = false;
        for (JsonNode itemNode : outputNode) {
          if (!itemNode.isObject()) {
            rawOutputItems.add(itemNode.deepCopy());
            continue;
          }

          ObjectNode item = (ObjectNode) itemNode.deepCopy();
          String itemType = item.path("type").asText();
          if ("reasoning".equals(itemType)) {
            retainStreamedReasoningEncryptedContent(item, priorStreamedItems);
          }

          rawOutputItems.add(item);
          if ("function_call".equals(itemType)) {
            syncToolFromItem(item);
          } else if ("message".equals(itemType)) {
            appendMessageContent(item.get("content"));
          } else if ("reasoning".equals(itemType)) {
            terminalReasoningItemSeen = true;
            JsonNode summary = item.get("summary");
            if (summary != null) {
              if (summary.isArray()) {
                for (JsonNode s : summary) {
                  if (s.isObject() && "summary_text".equals(s.path("type").asText())) {
                    String summaryText = s.path("text").asText("");
                    thinkingBuffer.append(summaryText);
                    if (!summaryText.isBlank()) {
                      terminalUsableSummarySeen = true;
                    }
                  }
                }
              } else if (summary.isTextual()) {
                String summaryText = summary.asText();
                thinkingBuffer.append(summaryText);
                if (!summaryText.isBlank()) {
                  terminalUsableSummarySeen = true;
                }
              }
            }
          }
        }
        // 终态声明了 reasoning 却没有任何可用摘要文本时（例如 MiniMax 的 summary:[] 占位符），流式思考是唯一可得的
        // 语义表示，必须保留给 durable 消息；非空的权威终态摘要仍然优先，未被终态声明的思考仍按既有语义清除。
        if (terminalReasoningItemSeen
            && !terminalUsableSummarySeen
            && !priorStreamedThinking.isBlank()) {
          // 丢弃终态给出的纯空白摘要，避免与流式思考拼接出额外空白。
          thinkingBuffer.setLength(0);
          thinkingBuffer.append(priorStreamedThinking);
        }
      }
    }
  }

  ProviderCompletion finish() {
    if (finishedCompletion != null) {
      return finishedCompletion;
    }
    if (!terminalReceived) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "OpenAI Responses stream ended unexpectedly before complete message received (premature EOF)");
    }
    if (stopReason == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "missing stopReason in OpenAI Responses response");
    }

    List<ProviderToolCall> toolCalls = new ArrayList<>();
    List<ProviderToolCallDiagnostic> toolCallDiagnostics = new ArrayList<>();
    boolean canReplay =
        (stopReason == GenerationStopReason.COMPLETE || stopReason == GenerationStopReason.LENGTH);

    if (!explicitTerminalOutputProcessed) {
      // 从 rawOutputItems 中提取权威文本与思考内容（若有且未在流式 delta 中收到）
      for (JsonNode item : rawOutputItems) {
        String itemType = item.path("type").asText();
        if ("message".equals(itemType)) {
          JsonNode content = item.get("content");
          if (content != null && textBuffer.isEmpty()) {
            appendMessageContent(content);
          }
        } else if ("reasoning".equals(itemType)) {
          JsonNode summary = item.get("summary");
          if (summary != null && summary.isArray() && thinkingBuffer.isEmpty()) {
            for (JsonNode s : summary) {
              if ("summary_text".equals(s.path("type").asText())) {
                thinkingBuffer.append(s.path("text").asText(""));
              }
            }
          }
        }
      }
    }

    if (stopReason == GenerationStopReason.FILTERED) {
      toolCalls.clear();
      toolCallDiagnostics.clear();
      canReplay = false;
    } else {
      // 评估所有工具调用的完整性，严禁合成 {}
      int ordinal = 0;
      for (WireToolCall tool : toolsById.values()) {
        String effectiveCallId =
            (tool.callId != null && !tool.callId.isBlank()) ? tool.callId : tool.id;
        boolean hasValidId = effectiveCallId != null && !effectiveCallId.isBlank();
        boolean hasValidName = tool.name != null && !tool.name.isBlank();
        boolean hasArgs = tool.argumentsPresent || tool.argumentsBuffer.length() > 0;
        boolean isTextual = tool.argumentsTextual;
        String argsStr = tool.argumentsBuffer.toString();
        JsonNode parsedArgs =
            (isTextual && hasArgs && !argsStr.isBlank()) ? tryParseJsonObject(argsStr) : null;

        if (stopReason == GenerationStopReason.COMPLETE) {
          if (!hasValidId) {
            throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "tool call missing id");
          }
          if (!hasValidName) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "tool call missing name");
          }
          if (!hasArgs || argsStr.isEmpty()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "incomplete tool call missing arguments");
          }
          if (!isTextual) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "tool call arguments must be a JSON string");
          }
          if (parsedArgs == null) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE,
                "tool call arguments cannot be parsed as JSON object");
          }
          toolCalls.add(new ProviderToolCall(effectiveCallId, tool.name, argsStr));
        } else {
          // stopReason == GenerationStopReason.LENGTH
          boolean valid = hasValidId && hasValidName && hasArgs && isTextual && parsedArgs != null;
          if (valid) {
            toolCalls.add(new ProviderToolCall(effectiveCallId, tool.name, argsStr));
          } else {
            String diagId = hasValidId ? effectiveCallId : null;
            String diagName = hasValidName ? tool.name : null;
            String partialArgs = (isTextual && !argsStr.isEmpty()) ? argsStr : null;
            String reason;
            if (!hasValidId || !hasValidName) {
              reason = "tool call truncated before identity received";
            } else if (!hasArgs || argsStr.isEmpty()) {
              reason = "tool call truncated before arguments received";
            } else {
              reason = "tool call arguments truncated or malformed";
            }
            toolCallDiagnostics.add(
                new ProviderToolCallDiagnostic(ordinal, diagId, diagName, partialArgs, reason));
            canReplay = false;
          }
        }
        ordinal++;
      }
    }

    if (!toolCallDiagnostics.isEmpty()) {
      canReplay = false;
    }

    // Token 用量归一化：total_tokens 缺失时为 0L，不凭空合成
    long ordinaryInput = Math.max(0L, rawInputTokens - cachedTokens - cacheWriteTokens);
    long ordinaryOutput = Math.max(0L, rawOutputTokens - reasoningTokens);
    long providerTotalTokens = hasRawTotalTokens ? rawTotalTokens : 0L;

    ModelUsage usage =
        new ModelUsage(
            ordinaryInput,
            ordinaryOutput,
            cachedTokens,
            cacheWriteTokens,
            0L,
            reasoningTokens,
            providerTotalTokens);
    ModelCost cost = ModelCost.calculate(request.model().pricing(), usage);

    // rawUsageJson 高保真：直接保留上游原生 usage 结构，缺失时不伪造字段；无 usage 时产出 {}
    String rawUsageJson;
    if (latestNativeUsageNode != null) {
      try {
        rawUsageJson = OBJECT_MAPPER.writeValueAsString(latestNativeUsageNode);
      } catch (Exception e) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "failed to serialize raw usage JSON");
      }
    } else {
      rawUsageJson = "{}";
    }

    ProviderResponse response =
        new ProviderResponse(
            textBuffer.toString(),
            thinkingBuffer.toString(),
            Collections.unmodifiableList(toolCalls),
            stopReason,
            usage,
            cost,
            requestId,
            serviceTier,
            rawUsageJson,
            Collections.unmodifiableList(toolCallDiagnostics));

    ProviderReplayState replayState = null;
    if (canReplay) {
      ArrayNode replayOutputArray = buildReplayOutputArray();
      // 只含空 reasoning 占位符的 replay 不承载任何原生推理，冻结它没有语义价值，交由下一轮语义编码。
      if (!replayOutputArray.isEmpty() && !replayIsEmptyPlaceholderOnly(replayOutputArray)) {
        ObjectNode payload = NODES.objectNode();
        payload.set("output", replayOutputArray);
        replayState =
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                descriptor.affinity(request.model().modelId()),
                frozenSourcePrefixHash,
                payload);
      }
    }

    finishedCompletion = new ProviderCompletion(response, replayState);
    return finishedCompletion;
  }

  ProviderResponse response() {
    return finish().response();
  }

  ProviderReplayState replayState() {
    return finish().replayState();
  }

  /**
   * 组装 stateless replay 的 output 数组。
   *
   * <p>官方已知 item（{@code message}/{@code reasoning}/{@code function_call}）以终态/{@code
   * output_item.done} 的权威 item 为唯一事实来源：整体深拷贝后只修复那些「不修复就会让下一轮必然失败或丢语义」的字段（assistant role、
   * 可读文本、可校验的函数调用标识与参数），其余字段（{@code id}/{@code status}/注解/日志概率及未来新增成员）一律 原样保留。未知官方 item
   * 继续作为不透明事实整体透传。
   */
  private ArrayNode buildReplayOutputArray() {
    ArrayNode array = NODES.arrayNode();
    for (JsonNode item : rawOutputItems) {
      if (!item.isObject()) {
        continue;
      }
      String type = item.path("type").asText();
      switch (type) {
        case "message" -> array.add(normalizeReplayMessageItem(item));
        case "reasoning" -> array.add(normalizeReplayReasoningItem(item));
        case "function_call" -> array.add(normalizeReplayFunctionCallItem(item));
        default -> {
          // 未知但可信的官方 output item（web_search_call / file_search_call / image_generation_call /
          // 自定义工具 item 等）：保留为不透明事实供下一轮 stateless replay，绝不再静默丢弃。
          if (isOpaqueReplayItem(item)) {
            array.add(item.deepCopy());
          }
        }
      }
    }

    // 若显式处理了 terminal output 且 rawOutputItems 为空（例如 output: []），
    // 则表示终端权威输出清空了所有内容，不得回退合成草稿 replay。
    if (explicitTerminalOutputProcessed && rawOutputItems.isEmpty()) {
      return array;
    }

    // 若 rawOutputItems 为空但有文本或工具调用，合成标准的 replay output
    if (array.isEmpty()) {
      if (!thinkingBuffer.isEmpty()) {
        ObjectNode reasoning = array.addObject();
        reasoning.put("type", "reasoning");
        ArrayNode sum = reasoning.putArray("summary");
        ObjectNode s = sum.addObject();
        s.put("type", "summary_text");
        s.put("text", thinkingBuffer.toString());
      }
      if (!textBuffer.isEmpty()) {
        ObjectNode msg = array.addObject();
        msg.put("type", "message");
        msg.put("role", "assistant");
        ArrayNode c = msg.putArray("content");
        ObjectNode t = c.addObject();
        t.put("type", "output_text");
        t.put("text", textBuffer.toString());
      }
      for (WireToolCall tool : toolsById.values()) {
        String effectiveCallId =
            (tool.callId != null && !tool.callId.isBlank()) ? tool.callId : tool.id;
        String args = tool.argumentsBuffer.toString();
        if (effectiveCallId == null
            || effectiveCallId.isBlank()
            || tool.name == null
            || tool.name.isBlank()
            || args.isBlank()
            || tryParseJsonObject(args) == null) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "invalid function call in replay construction");
        }
        ObjectNode fc = array.addObject();
        fc.put("type", "function_call");
        fc.put("call_id", effectiveCallId);
        fc.put("name", tool.name);
        fc.put("arguments", args);
      }
    }

    return array;
  }

  /**
   * 归一化 replay 中的 message item：整体深拷贝权威 item，只把 role 与可读 content 文本修成 durable 语义的载体形态。
   *
   * <p>官方响应事实（{@code id}/{@code status}/{@code phase}/content 块自身的 {@code annotations}/{@code
   * logprobs} 及未来新增成员）一律原样保留。无法通过下一轮结构校验的空白 {@code id}/{@code phase} 不承载任何事实，剔除以免毒化冻结 replay。
   */
  private static ObjectNode normalizeReplayMessageItem(JsonNode item) {
    ObjectNode msg = (ObjectNode) item.deepCopy();
    msg.put("type", "message");
    msg.put("role", "assistant");
    dropNonTextualValue(msg, "id");
    dropNonTextualValue(msg, "phase");

    ArrayNode contentArr = NODES.arrayNode();
    JsonNode srcContent = item.get("content");
    if (srcContent != null && srcContent.isArray()) {
      for (JsonNode c : srcContent) {
        if (!c.isObject()) {
          continue;
        }
        String blockType = c.path("type").asText();
        if ("output_text".equals(blockType)) {
          ObjectNode textNode = (ObjectNode) c.deepCopy();
          textNode.put("text", c.path("text").asText(""));
          contentArr.add(textNode);
        } else if ("refusal".equals(blockType)) {
          ObjectNode refusalNode = (ObjectNode) c.deepCopy();
          refusalNode.put("refusal", c.path("refusal").asText(""));
          contentArr.add(refusalNode);
        }
      }
    } else if (srcContent != null && srcContent.isTextual()) {
      ObjectNode textNode = contentArr.addObject();
      textNode.put("type", "output_text");
      textNode.put("text", srcContent.asText());
    }
    msg.set("content", contentArr);
    return msg;
  }

  /**
   * 归一化 replay 中的 reasoning item：深拷贝权威 item，仅把 summary 归一为可校验的 {@code summary_text} 数组形态。
   *
   * <p>{@code encrypted_content} 的合并保留（见 {@link #retainStreamedReasoningEncryptedContent}）保持原有语义；仅当
   * 密文不是非空白字符串时才剔除，因为它不承载任何原生推理且会让下一轮校验必然失败。其余 {@code id}/{@code status} 与未来成员原样保留。
   */
  private static ObjectNode normalizeReplayReasoningItem(JsonNode item) {
    ObjectNode reasoning = (ObjectNode) item.deepCopy();
    reasoning.put("type", "reasoning");
    dropNonTextualValue(reasoning, "id");
    dropNonTextualValue(reasoning, "encrypted_content");

    JsonNode summary = item.get("summary");
    if (summary == null) {
      return reasoning;
    }
    if (summary.isArray()) {
      ArrayNode sumArr = NODES.arrayNode();
      for (JsonNode s : summary) {
        if (!s.isObject() || !"summary_text".equals(s.path("type").asText())) {
          continue;
        }
        ObjectNode sNode = (ObjectNode) s.deepCopy();
        sNode.put("text", s.path("text").asText(""));
        sumArr.add(sNode);
      }
      reasoning.set("summary", sumArr);
      return reasoning;
    }
    if (summary.isTextual()) {
      ArrayNode sumArr = NODES.arrayNode();
      ObjectNode sNode = sumArr.addObject();
      sNode.put("type", "summary_text");
      sNode.put("text", summary.asText());
      reasoning.set("summary", sumArr);
      return reasoning;
    }
    // 非数组且非文本的 summary 无法构成任何合法摘要，属于无摘要事实
    reasoning.remove("summary");
    return reasoning;
  }

  /**
   * 归一化 replay 中的 function_call item：深拷贝权威 item，只用流式工具状态补齐 durable 语义必需的 {@code call_id}/{@code
   * name}/{@code arguments}，其余 {@code id}/{@code status} 与未来成员原样保留。
   *
   * <p>缺失或畸形的函数调用标识与参数无法被下一轮严格校验承载，因此与历史行为一致地抛出 {@code INVALID_RESPONSE}，绝不合成 {@code {}} 或伪造工具身份。
   */
  private ObjectNode normalizeReplayFunctionCallItem(JsonNode item) {
    String itemId = nonBlankText(item.get("id"));
    String callId = nonBlankText(item.get("call_id"));
    WireToolCall tool = findToolCall(itemId, callId);

    String effectiveCallId = callId != null ? callId : itemId;
    if (effectiveCallId == null && tool != null) {
      effectiveCallId = nonBlank(tool.callId) != null ? tool.callId : tool.id;
    }

    String name = nonBlankText(item.get("name"));
    if (name == null && tool != null) {
      name = nonBlank(tool.name);
    }

    String args = null;
    JsonNode argumentsNode = item.get("arguments");
    if (argumentsNode != null && argumentsNode.isTextual()) {
      args = argumentsNode.textValue();
    } else if (tool != null && tool.argumentsTextual && tool.argumentsBuffer.length() > 0) {
      args = tool.argumentsBuffer.toString();
    }

    if (effectiveCallId == null
        || effectiveCallId.isBlank()
        || name == null
        || name.isBlank()
        || args == null
        || args.isBlank()
        || tryParseJsonObject(args) == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid function call in replay construction");
    }

    ObjectNode fc = (ObjectNode) item.deepCopy();
    fc.put("type", "function_call");
    fc.put("call_id", effectiveCallId);
    fc.put("name", name);
    fc.put("arguments", args);
    dropNonTextualValue(fc, "id");
    return fc;
  }

  /** 只保留非空白字符串形态的字段值；空白、null 与非文本值都不承载协议事实，剔除它们避免冻结出必然失败的 replay。 */
  private static void dropNonTextualValue(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value != null && nonBlankText(value) == null) {
      node.remove(field);
    }
  }

  private static String nonBlankText(JsonNode node) {
    if (node == null || !node.isTextual()) {
      return null;
    }
    return nonBlank(node.textValue());
  }

  private static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private void appendMessageContent(JsonNode content) {
    if (content == null || content.isNull()) {
      return;
    }
    if (content.isTextual()) {
      textBuffer.append(content.asText());
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode block : content) {
      if (!block.isObject()) {
        continue;
      }
      String type = block.path("type").asText();
      if ("output_text".equals(type)) {
        textBuffer.append(block.path("text").asText(""));
      } else if ("refusal".equals(type)) {
        textBuffer.append(block.path("refusal").asText(""));
      }
    }
  }

  /**
   * 已知 output item 类型：它们有专门的归一化与 durable 语义校验，不是不透明事实。
   *
   * <p>{@code message}/{@code reasoning}/{@code function_call} 的权威表示来自 delta、done 与 terminal
   * output， 因此不会被 {@code output_item.added} 的早期形态取代；其余官方类型只以不透明事实存在。
   */
  private static boolean isKnownOutputItemType(String itemType) {
    return "message".equals(itemType)
        || "reasoning".equals(itemType)
        || "function_call".equals(itemType);
  }

  /**
   * 判断未知 type 的 output item 是否可作为不透明事实冻结。
   *
   * <p>只有具备非空白字符串 {@code type} 的对象才是可回放的协议 item；冻结缺少 type 的碎片只会让下一轮编码必然失败，因此这类碎片不进入 replay。
   */
  private static boolean isOpaqueReplayItem(JsonNode item) {
    JsonNode typeNode = item.get("type");
    return typeNode != null && typeNode.isTextual() && !typeNode.textValue().isBlank();
  }

  /**
   * 判断 replay output 是否只由“无法承载 semantic thinking 的空 reasoning 占位符”构成。
   *
   * <p>占位符指既无 {@code encrypted_content}、也无任何非空摘要文本的 reasoning item。至少要有一个 reasoning item
   * 才构成该形态；含密文的 opaque reasoning（必须原样保留，绝不剥离）或含非空摘要的 reasoning 都不属于此形态。
   */
  private static boolean replayIsEmptyPlaceholderOnly(ArrayNode replayOutputArray) {
    boolean reasoningItemSeen = false;
    for (JsonNode item : replayOutputArray) {
      if (!"reasoning".equals(item.path("type").asText())) {
        continue;
      }
      reasoningItemSeen = true;
      if (!item.path("encrypted_content").asText("").isBlank()) {
        return false;
      }
      JsonNode summary = item.get("summary");
      if (summary != null) {
        for (JsonNode s : summary) {
          if (!s.path("text").asText("").isBlank()) {
            return false;
          }
        }
      }
    }
    return reasoningItemSeen;
  }

  private static JsonNode parseJson(String data) {
    try {
      return OBJECT_MAPPER.readTree(data);
    } catch (Exception e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE event data is not valid JSON");
    }
  }

  private static JsonNode tryParseJsonObject(String str) {
    if (str == null || str.isBlank()) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(str);
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }
}
