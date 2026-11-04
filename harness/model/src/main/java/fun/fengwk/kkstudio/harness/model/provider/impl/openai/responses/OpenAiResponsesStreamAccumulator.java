package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamState;

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
 * <p>每条 transport 帧在进入状态机之前先经 {@link ProviderStreamBridge#emitProtocolEvent} exactly-once
 * 上报原生事实；原生帧不进入 durable checkpoint、realtime 增量或任何内容缓冲。
 */
public final class OpenAiResponsesStreamAccumulator extends ProviderStreamState {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String USAGE_INTEGER_FORMAT =
      "invalid usage token count in field %s: must be an integral number within long range";
  private static final String USAGE_NEGATIVE_FORMAT =
      "invalid usage token count in field %s: must be non-negative";

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
  private final ProviderStreamBridge bridge;

  private boolean terminalReceived = false;
  private String requestId = null;
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
  private Long reasoningTokens = null;
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
      ProviderRequest request, ProviderDescriptor descriptor, ProviderStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
  }

  public OpenAiResponsesStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      Consumer<ProviderStreamEvent> eventConsumer) {
    this(request, descriptor, createBridge(eventConsumer));
  }

  private static ProviderStreamBridge createBridge(Consumer<ProviderStreamEvent> eventConsumer) {
    return new ProviderStreamBridge(
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {
            if (eventConsumer != null) {
              eventConsumer.accept(event);
            }
          }

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {}
        });
  }

  public void handleEvent(String eventName, String data) {
    if (bridge.isClosed()) {
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

    JsonNode node = ProviderJsonHelper.parseSseJson(data, "SSE event data is not valid JSON");
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
    JsonNode node = ProviderJsonHelper.tryParseJsonObject(payload);
    if (node != null) {
      JsonNode typeNode = node.get("type");
      if (typeNode != null && typeNode.isString() && !typeNode.stringValue().isBlank()) {
        return new ProviderProtocolEvent(typeNode.stringValue(), payload);
      }
    }
    return new ProviderProtocolEvent(UNKNOWN_PROTOCOL_EVENT_TYPE, payload);
  }

  public void processEvent(JsonNode node) {
    if (node == null || !node.isObject()) {
      return;
    }
    String type = node.path("type").asString("");

    if ("response.failed".equals(type) || "response.error".equals(type) || "error".equals(type)) {
      throw OpenAiResponsesErrorMapper.mapSseErrorEnvelope(node);
    }

    if (!KNOWN_SSE_EVENTS.contains(type)) {
      return;
    }
    if (terminalReceived) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "semantic event received after terminal response");
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
    String delta = node.path("delta").asString("");
    if (!delta.isEmpty()) {
      textBuffer.append(delta);
      bridge.emitEvent(new ProviderStreamEvent.TextDelta(delta));
    }
  }

  private void handleThinkingDelta(JsonNode node) {
    String delta = node.path("delta").asString("");
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
    String itemType = item.path("type").asString("");
    if ("function_call".equals(itemType)) {
      String id = item.path("id").asString(null);
      if (id == null || id.isBlank()) {
        return;
      }
      String callId = item.path("call_id").asString(null);
      if (callId == null || callId.isBlank()) {
        callId = id;
      }
      String name = item.path("name").asString(null);
      // 首次观察到的 function_call 分配下一个连续 ordinal；重复 added 复用既有 ordinal 以避免序号空洞
      WireToolCall previous = toolsById.get(id);
      if (previous != null) {
        if (!sameToolIdentity(previous, callId, name)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "duplicate output item has a different identity");
        }
        return;
      }
      int ordinal = nextToolOrdinal++;
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
    String itemId = node.path("item_id").asString(null);
    String delta = node.path("delta").asString("");
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
    String itemId = node.path("item_id").asString(null);
    String arguments = node.path("arguments").asString(null);
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

  private static boolean sameToolIdentity(WireToolCall tool, String callId, String name) {
    return Objects.equals(tool.callId, callId) && Objects.equals(tool.name, name);
  }

  private static long usageRemainder(long total, long... parts) {
    long excluded = 0L;
    try {
      for (long part : parts) {
        excluded = Math.addExact(excluded, part);
      }
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values overflow");
    }
    if (excluded > total) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage breakdown exceeds total");
    }
    return total - excluded;
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
    if (terminalId == null || !terminalId.isString() || terminalId.stringValue().isBlank()) {
      return;
    }

    for (JsonNode streamedItem : streamedItems) {
      if (!streamedItem.isObject()
          || !"reasoning".equals(streamedItem.path("type").asString(""))
          || !terminalId.stringValue().equals(streamedItem.path("id").asString(null))) {
        continue;
      }
      JsonNode encryptedContent = streamedItem.get("encrypted_content");
      if (encryptedContent != null
          && encryptedContent.isString()
          && !encryptedContent.stringValue().isBlank()) {
        terminalItem.set("encrypted_content", encryptedContent.deepCopy());
      }
      return;
    }
  }

  private void syncToolFromItem(JsonNode item) {
    String itemId = item.path("id").asString(null);
    String callId = item.path("call_id").asString(null);
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
      tool.name = item.path("name").asString(null);
    }
    if (item.has("arguments")) {
      if (item.get("arguments") != null && !item.get("arguments").isNull()) {
        tool.argumentsPresent = true;
        tool.argumentsTextual = item.get("arguments").isString();
        if (tool.argumentsTextual) {
          tool.argumentsBuffer.setLength(0);
          tool.argumentsBuffer.append(item.get("arguments").asString(""));
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
    if (item == null
        || !item.isObject()
        || !OpenAiResponsesReplayBuilder.isOpaqueReplayItem(item)) {
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
      // 槽位键取索引的完整十进制文字：任意宽度的整数坐标都不会因 int 截断而与其他 item 共用同一槽位。
      return "index:" + outputIndex.bigIntegerValue();
    }
    JsonNode id = item.get("id");
    if (id != null && id.isString() && !id.stringValue().isBlank()) {
      return "id:" + id.stringValue();
    }
    return null;
  }

  private void handleOutputItemDone(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject()) {
      return;
    }
    String itemType = item.path("type").asString("");
    if (!isKnownOutputItemType(itemType)) {
      retainStreamedOpaqueItem(node);
      return;
    }
    if ("function_call".equals(itemType)) {
      syncToolFromItem(item);
    } else if ("reasoning".equals(itemType)) {
      // content 是已知语义输入：捕获阶段即拒绝已知坏形状，避免冻结出下一轮必然失败的 replay。
      OpenAiResponsesReasoningText.requireValidContent(
          item.get("content"), ProviderErrorKind.INVALID_RESPONSE);
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
      reason = responseNode.path("incomplete_details").path("reason").asString("");
    }
    if ("content_filter".equals(reason)) {
      stopReason = GenerationStopReason.FILTERED;
    } else {
      stopReason = GenerationStopReason.LENGTH;
    }
    extractResponseData(responseNode, true);
  }

  private static long usageToken(JsonNode node, String fieldName) {
    return ProviderJsonHelper.parseNonNegativeLong(
        node, fieldName, USAGE_INTEGER_FORMAT, USAGE_NEGATIVE_FORMAT);
  }

  private void resetUsage() {
    latestNativeUsageNode = null;
    rawInputTokens = 0L;
    rawOutputTokens = 0L;
    hasRawTotalTokens = false;
    rawTotalTokens = 0L;
    cachedTokens = 0L;
    cacheWriteTokens = 0L;
    reasoningTokens = null;
  }

  private void extractResponseData(JsonNode responseNode, boolean isTerminal) {
    if (responseNode == null || !responseNode.isObject()) {
      return;
    }
    if (responseNode.has("id") && !responseNode.path("id").asString("").isBlank()) {
      requestId = responseNode.path("id").asString("");
    }
    if (responseNode.has("service_tier")
        && !responseNode.path("service_tier").asString("").isBlank()) {
      serviceTier = responseNode.path("service_tier").asString("");
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

        if (!usageNode.has("input_tokens") || !usageNode.has("output_tokens")) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "missing complete usage: OpenAI Responses model " + request.model().modelId());
        }
        rawInputTokens = usageToken(usageNode.get("input_tokens"), "input_tokens");
        rawOutputTokens = usageToken(usageNode.get("output_tokens"), "output_tokens");

        JsonNode totalNode = usageNode.get("total_tokens");
        if (totalNode != null && !totalNode.isNull()) {
          hasRawTotalTokens = true;
          rawTotalTokens = usageToken(totalNode, "total_tokens");
        }

        JsonNode inDetails = usageNode.get("input_tokens_details");
        if (inDetails != null && !inDetails.isNull()) {
          if (!inDetails.isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "input_tokens_details is not a JSON object");
          }
          cachedTokens =
              inDetails.has("cached_tokens")
                  ? usageToken(inDetails.get("cached_tokens"), "cached_tokens")
                  : 0L;
          cacheWriteTokens =
              inDetails.has("cache_write_tokens")
                  ? usageToken(inDetails.get("cache_write_tokens"), "cache_write_tokens")
                  : 0L;
        }

        JsonNode outDetails = usageNode.get("output_tokens_details");
        if (outDetails != null && !outDetails.isNull()) {
          if (!outDetails.isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "output_tokens_details is not a JSON object");
          }
          if (outDetails.has("reasoning_tokens") && !outDetails.get("reasoning_tokens").isNull()) {
            reasoningTokens = usageToken(outDetails.get("reasoning_tokens"), "reasoning_tokens");
          }
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
        for (JsonNode itemNode : outputNode) {
          if (!itemNode.isObject()) {
            rawOutputItems.add(itemNode.deepCopy());
            continue;
          }

          ObjectNode item = (ObjectNode) itemNode.deepCopy();
          String itemType = item.path("type").asString("");
          if ("reasoning".equals(itemType)) {
            retainStreamedReasoningEncryptedContent(item, priorStreamedItems);
            // content 是已知语义输入：捕获阶段即拒绝已知坏形状，避免冻结出下一轮必然失败的 replay。
            OpenAiResponsesReasoningText.requireValidContent(
                item.get("content"), ProviderErrorKind.INVALID_RESPONSE);
          }

          rawOutputItems.add(item);
          if ("function_call".equals(itemType)) {
            syncToolFromItem(item);
          } else if ("message".equals(itemType)) {
            appendMessageContent(item.get("content"));
          }
        }
        rebuildThinkingFromItems(priorStreamedThinking);
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
    if (latestNativeUsageNode == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: OpenAI Responses model " + request.model().modelId());
    }

    List<ProviderToolCall> toolCalls = new ArrayList<>();
    List<ProviderToolCallDiagnostic> toolCallDiagnostics = new ArrayList<>();
    boolean canReplay =
        (stopReason == GenerationStopReason.COMPLETE || stopReason == GenerationStopReason.LENGTH);

    if (!explicitTerminalOutputProcessed) {
      // 从 rawOutputItems 中提取权威文本与思考内容（若有且未在流式 delta 中收到）
      for (JsonNode item : rawOutputItems) {
        String itemType = item.path("type").asString("");
        if ("message".equals(itemType)) {
          JsonNode content = item.get("content");
          if (content != null && textBuffer.isEmpty()) {
            appendMessageContent(content);
          }
        }
      }
      rebuildThinkingFromItems(thinkingBuffer.toString());
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
            (isTextual && hasArgs && !argsStr.isBlank())
                ? ProviderJsonHelper.tryParseJsonObject(argsStr)
                : null;

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

    // Token 用量归一化：total_tokens 缺失时为 null，不凭空合成；输出总量包含 reasoning。
    long ordinaryInput = usageRemainder(rawInputTokens, cachedTokens, cacheWriteTokens);
    Long providerTotalTokens = hasRawTotalTokens ? rawTotalTokens : null;

    ModelUsage usage;
    try {
      usage =
          new ModelUsage(
              ordinaryInput,
              rawOutputTokens,
              cachedTokens,
              cacheWriteTokens,
              Map.of(),
              reasoningTokens,
              providerTotalTokens);
    } catch (IllegalArgumentException invalidUsage) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values are inconsistent");
    }

    // 完整 usage 已在终态校验；原生结构高保真保留，不伪造字段。
    String rawUsageJson;
    try {
      rawUsageJson = OBJECT_MAPPER.writeValueAsString(latestNativeUsageNode);
    } catch (Exception e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "failed to serialize raw usage JSON");
    }

    ProviderResponse response =
        new ProviderResponse(
            textBuffer.toString(),
            thinkingBuffer.toString(),
            Collections.unmodifiableList(toolCalls),
            stopReason,
            usage,
            requestId,
            serviceTier,
            rawUsageJson,
            Collections.unmodifiableList(toolCallDiagnostics));

    ProviderReplayState replayState = null;
    if (canReplay) {
      ArrayNode replayOutputArray =
          OpenAiResponsesReplayBuilder.build(
              rawOutputItems,
              explicitTerminalOutputProcessed,
              thinkingBuffer.toString(),
              textBuffer.toString(),
              replayTools());
      // 只含空 reasoning 占位符的 replay 不承载任何原生推理，冻结它没有语义价值，交由下一轮语义编码。
      if (!replayOutputArray.isEmpty()
          && !OpenAiResponsesReplayItems.isEmptyPlaceholderOnly(replayOutputArray)) {
        ObjectNode payload = NODES.objectNode();
        payload.set("output", replayOutputArray);
        replayState =
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                descriptor.affinity(request.model().modelId()),
                payload);
      }
    }

    finishedCompletion = new ProviderCompletion(response, replayState);
    return finishedCompletion;
  }

  /** 权威 item 按顺序聚合；全部无可比文本时保留流式思考，绝不混入草稿。 */
  private void rebuildThinkingFromItems(String streamedThinking) {
    boolean reasoningSeen = false;
    thinkingBuffer.setLength(0);
    for (JsonNode item : rawOutputItems) {
      if ("reasoning".equals(item.path("type").asString(""))) {
        reasoningSeen = true;
        thinkingBuffer.append(
            OpenAiResponsesReasoningText.read(item, ProviderErrorKind.INVALID_RESPONSE));
      }
    }
    if (thinkingBuffer.isEmpty()
        && (reasoningSeen || !explicitTerminalOutputProcessed)
        && !streamedThinking.isBlank()) {
      thinkingBuffer.append(streamedThinking);
    }
  }

  public ProviderResponse response() {
    return finish().response();
  }

  public ProviderReplayState replayState() {
    return finish().replayState();
  }

  /** 把流内工具状态冻结为 replay 重建所需的不可变快照。 */
  private List<OpenAiResponsesReplayBuilder.ReplayTool> replayTools() {
    List<OpenAiResponsesReplayBuilder.ReplayTool> tools = new ArrayList<>(toolsById.size());
    for (Map.Entry<String, WireToolCall> entry : toolsById.entrySet()) {
      WireToolCall tool = entry.getValue();
      tools.add(
          new OpenAiResponsesReplayBuilder.ReplayTool(
              entry.getKey(),
              tool.id,
              tool.callId,
              tool.name,
              tool.argumentsBuffer.toString(),
              tool.argumentsTextual));
    }
    return tools;
  }

  private void appendMessageContent(JsonNode content) {
    if (content == null || content.isNull()) {
      return;
    }
    if (content.isString()) {
      textBuffer.append(content.asString(""));
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode block : content) {
      if (!block.isObject()) {
        continue;
      }
      String type = block.path("type").asString("");
      if ("output_text".equals(type)) {
        textBuffer.append(block.path("text").asString(""));
      } else if ("refusal".equals(type)) {
        textBuffer.append(block.path("refusal").asString(""));
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
}
