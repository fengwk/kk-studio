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
 * <p>按协议规范解析文本增量、推理增量与工具调用增量，处理 completed/incomplete/failed 事件， 进行细粒度用量归一化与白名单 native replay 组装。
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
          "response.reasoning_text.delta",
          "response.reasoning_summary_text.delta",
          "response.output_item.added",
          "response.function_call_arguments.delta",
          "response.function_call_arguments.done",
          "response.output_item.done",
          "response.completed",
          "response.incomplete",
          "response.failed",
          "response.error");

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

  void processEvent(JsonNode node) {
    if (node == null || !node.isObject()) {
      return;
    }
    String type = node.path("type").asText();

    if ("response.failed".equals(type) || "response.error".equals(type)) {
      throw OpenAiResponsesErrorMapper.mapSseErrorEnvelope(node);
    }

    if (!KNOWN_SSE_EVENTS.contains(type)) {
      return;
    }

    switch (type) {
      case "response.created" -> handleCreated(node);
      case "response.output_text.delta" -> handleTextDelta(node);
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

  private void handleOutputItemDone(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject()) {
      return;
    }
    String itemType = item.path("type").asText();
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
        rawOutputItems.clear();
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
          String itemType = item.path("type").asText();
          if ("reasoning".equals(itemType)) {
            retainStreamedReasoningEncryptedContent(item, priorStreamedItems);
          }

          rawOutputItems.add(item);
          if ("function_call".equals(itemType)) {
            syncToolFromItem(item);
          } else if ("message".equals(itemType)) {
            JsonNode content = item.get("content");
            if (content != null) {
              if (content.isArray()) {
                for (JsonNode c : content) {
                  if (c.isObject() && "output_text".equals(c.path("type").asText())) {
                    textBuffer.append(c.path("text").asText(""));
                  }
                }
              } else if (content.isTextual()) {
                textBuffer.append(content.asText());
              }
            }
          } else if ("reasoning".equals(itemType)) {
            JsonNode summary = item.get("summary");
            if (summary != null) {
              if (summary.isArray()) {
                for (JsonNode s : summary) {
                  if (s.isObject() && "summary_text".equals(s.path("type").asText())) {
                    thinkingBuffer.append(s.path("text").asText(""));
                  }
                }
              } else if (summary.isTextual()) {
                thinkingBuffer.append(summary.asText());
              }
            }
          }
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
          if (content != null && content.isArray() && textBuffer.isEmpty()) {
            for (JsonNode c : content) {
              if ("output_text".equals(c.path("type").asText())) {
                textBuffer.append(c.path("text").asText(""));
              }
            }
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
      if (!replayOutputArray.isEmpty()) {
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

  private ArrayNode buildReplayOutputArray() {
    ArrayNode array = NODES.arrayNode();
    for (JsonNode item : rawOutputItems) {
      if (!item.isObject()) {
        continue;
      }
      String type = item.path("type").asText();
      switch (type) {
        case "message" -> {
          ObjectNode msg = array.addObject();
          msg.put("type", "message");
          msg.put("role", "assistant");
          if (item.has("phase") && !item.path("phase").asText().isBlank()) {
            msg.put("phase", item.path("phase").asText());
          }
          ArrayNode contentArr = msg.putArray("content");
          JsonNode srcContent = item.get("content");
          if (srcContent != null && srcContent.isArray()) {
            for (JsonNode c : srcContent) {
              if (c.isObject() && "output_text".equals(c.path("type").asText())) {
                ObjectNode textNode = contentArr.addObject();
                textNode.put("type", "output_text");
                textNode.put("text", c.path("text").asText(""));
              }
            }
          } else if (srcContent != null && srcContent.isTextual()) {
            ObjectNode textNode = contentArr.addObject();
            textNode.put("type", "output_text");
            textNode.put("text", srcContent.asText());
          }
        }
        case "reasoning" -> {
          ObjectNode reasoning = array.addObject();
          reasoning.put("type", "reasoning");
          if (item.has("encrypted_content") && !item.path("encrypted_content").asText().isBlank()) {
            reasoning.put("encrypted_content", item.path("encrypted_content").asText());
          }
          JsonNode summary = item.get("summary");
          if (summary != null && summary.isArray()) {
            ArrayNode sumArr = reasoning.putArray("summary");
            for (JsonNode s : summary) {
              if (s.isObject() && "summary_text".equals(s.path("type").asText())) {
                ObjectNode sNode = sumArr.addObject();
                sNode.put("type", "summary_text");
                sNode.put("text", s.path("text").asText(""));
              }
            }
          } else if (summary != null && summary.isTextual()) {
            ArrayNode sumArr = reasoning.putArray("summary");
            ObjectNode sNode = sumArr.addObject();
            sNode.put("type", "summary_text");
            sNode.put("text", summary.asText());
          }
        }
        case "function_call" -> {
          String itemId = item.path("id").asText(null);
          String callId = item.path("call_id").asText(null);
          WireToolCall tool = findToolCall(itemId, callId);

          String effectiveCallId = null;
          if (callId != null && !callId.isBlank()) {
            effectiveCallId = callId;
          } else if (itemId != null && !itemId.isBlank()) {
            effectiveCallId = itemId;
          } else if (tool != null) {
            effectiveCallId =
                (tool.callId != null && !tool.callId.isBlank()) ? tool.callId : tool.id;
          }

          String name = null;
          if (item.has("name") && !item.path("name").asText().isBlank()) {
            name = item.path("name").asText();
          } else if (tool != null && tool.name != null && !tool.name.isBlank()) {
            name = tool.name;
          }

          String args = null;
          if (item.has("arguments") && item.get("arguments").isTextual()) {
            args = item.get("arguments").asText();
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

          ObjectNode fc = array.addObject();
          fc.put("type", "function_call");
          fc.put("call_id", effectiveCallId);
          fc.put("name", name);
          fc.put("arguments", args);
        }
        default -> {}
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
