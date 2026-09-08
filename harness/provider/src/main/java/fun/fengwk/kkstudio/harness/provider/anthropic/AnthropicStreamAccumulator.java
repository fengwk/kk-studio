package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Anthropic Messages SSE 流式事件状态机与响应累积器。
 *
 * <p>按原生 index 维护严格 indexed content block 状态机，支持多工具调用交错 delta、连续 toolOrdinal、累计 usage 快照更新、LENGTH
 * 截断诊断与 native replay 隔离。
 */
final class AnthropicStreamAccumulator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> KNOWN_SSE_EVENTS =
      Set.of(
          "message_start",
          "content_block_start",
          "content_block_delta",
          "content_block_stop",
          "message_delta",
          "message_stop");

  private static final Set<String> ALLOWED_CACHE_MISS_REASONS =
      Set.of(
          "model_changed",
          "system_changed",
          "tools_changed",
          "messages_changed",
          "previous_message_not_found",
          "unavailable");

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final String frozenSourcePrefixHash;
  private final AnthropicStreamBridge bridge;

  private boolean started = false;
  private boolean stopped = false;
  private Integer lastBlockIndex = null;
  private int nextToolOrdinal = 0;
  private String messageId = null;

  private final Map<Integer, WireBlockAccumulator> blocksByIndex = new TreeMap<>();

  private long inputTokens = 0L;
  private long cacheReadInputTokens = 0L;
  private long cacheCreationInputTokens = 0L;
  private long cacheCreation5m = 0L;
  private long cacheCreation1h = 0L;
  private long outputTokens = 0L;
  private boolean hasExplicitCacheBreakdown = false;

  private String cacheMissReasonType = null;
  private Long cacheMissedInputTokens = null;

  private GenerationStopReason stopReason = null;

  AnthropicStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      String frozenSourcePrefixHash,
      AnthropicStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.frozenSourcePrefixHash =
        Objects.requireNonNull(frozenSourcePrefixHash, "frozenSourcePrefixHash");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
  }

  /** 处理单个 SSE 事件。 */
  void handleEvent(String eventName, String data) {
    if (bridge.isCancelled()) {
      return;
    }
    if (eventName == null || eventName.isBlank() || "ping".equals(eventName)) {
      return;
    }
    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
      return;
    }
    if ("error".equals(eventName)) {
      JsonNode node = parseJson(data);
      throw AnthropicErrorMapper.mapSseErrorEnvelope(node);
    }
    if (!KNOWN_SSE_EVENTS.contains(eventName)) {
      // 安全忽略未知未来事件类型
      return;
    }

    JsonNode node = parseJson(data);
    if (!node.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE event data is not a JSON object");
    }
    if (!node.has("type")
        || !node.get("type").isTextual()
        || !eventName.equals(node.get("type").textValue())) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "mismatched SSE event type");
    }

    switch (eventName) {
      case "message_start" -> handleMessageStart(node);
      case "content_block_start" -> handleContentBlockStart(node);
      case "content_block_delta" -> handleContentBlockDelta(node);
      case "content_block_stop" -> handleContentBlockStop(node);
      case "message_delta" -> handleMessageDelta(node);
      case "message_stop" -> handleMessageStop(node);
      default -> {}
    }
  }

  /** 在流传输自然结束或接收到完整报文后触发完成组装。 */
  ProviderCompletion finish() {
    if (!started || !stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "Anthropic stream ended unexpectedly before complete message received");
    }
    if (stopReason == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "missing stop_reason in Anthropic response");
    }

    StringBuilder combinedText = new StringBuilder();
    StringBuilder combinedThinking = new StringBuilder();
    List<ProviderToolCall> toolCalls = new ArrayList<>();
    List<ProviderToolCallDiagnostic> toolCallDiagnostics = new ArrayList<>();

    boolean canReplay =
        (stopReason == GenerationStopReason.COMPLETE || stopReason == GenerationStopReason.LENGTH);
    ArrayNode replayBlocks = NODES.arrayNode();

    // blocksByIndex 升序遍历，严格保持原生 block index 顺序
    for (WireBlockAccumulator block : blocksByIndex.values()) {
      switch (block.type) {
        case "text" -> {
          combinedText.append(block.textBuffer);
          if (canReplay) {
            ObjectNode obj = replayBlocks.addObject();
            obj.put("type", "text");
            obj.put("text", block.textBuffer.toString());
          }
        }
        case "thinking" -> {
          combinedThinking.append(block.thinkingBuffer);
          if (block.signature == null || block.signature.isBlank()) {
            canReplay = false;
          } else if (canReplay) {
            ObjectNode obj = replayBlocks.addObject();
            obj.put("type", "thinking");
            obj.put("thinking", block.thinkingBuffer.toString());
            obj.put("signature", block.signature);
          }
        }
        case "redacted_thinking" -> {
          if (block.redactedData == null || block.redactedData.isBlank()) {
            canReplay = false;
          } else if (canReplay) {
            ObjectNode obj = replayBlocks.addObject();
            obj.put("type", "redacted_thinking");
            obj.put("data", block.redactedData);
          }
        }
        case "tool_use" -> {
          if (stopReason == GenerationStopReason.FILTERED) {
            // FILTERED 协议撤回，忽略工具处理
            break;
          }
          String argsStr = block.toolArgsBuffer.toString();
          if (argsStr.isEmpty()) {
            if (block.initialInputPlaceholder) {
              // 终态补齐 {}
              ProviderToolCall call = new ProviderToolCall(block.toolId, block.toolName, "{}");
              toolCalls.add(call);
              if (canReplay) {
                ObjectNode obj = replayBlocks.addObject();
                obj.put("type", "tool_use");
                obj.put("id", block.toolId);
                obj.put("name", block.toolName);
                obj.putObject("input");
              }
            } else {
              if (stopReason == GenerationStopReason.LENGTH) {
                toolCallDiagnostics.add(
                    new ProviderToolCallDiagnostic(
                        block.toolOrdinal,
                        block.toolId,
                        block.toolName,
                        null,
                        "tool call truncated before arguments received"));
                canReplay = false;
              } else {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE, "incomplete tool call missing arguments");
              }
            }
          } else {
            JsonNode parsedArgs = tryParseJsonObject(argsStr);
            if (parsedArgs != null) {
              ProviderToolCall call = new ProviderToolCall(block.toolId, block.toolName, argsStr);
              toolCalls.add(call);
              if (canReplay) {
                ObjectNode obj = replayBlocks.addObject();
                obj.put("type", "tool_use");
                obj.put("id", block.toolId);
                obj.put("name", block.toolName);
                obj.set("input", parsedArgs);
              }
            } else {
              if (stopReason == GenerationStopReason.LENGTH) {
                toolCallDiagnostics.add(
                    new ProviderToolCallDiagnostic(
                        block.toolOrdinal,
                        block.toolId,
                        block.toolName,
                        argsStr,
                        "tool arguments truncated before valid JSON formed"));
                canReplay = false;
              } else {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE, "invalid tool arguments JSON");
              }
            }
          }
        }
        default -> canReplay = false;
      }
    }

    if (stopReason == GenerationStopReason.FILTERED) {
      toolCalls.clear();
      toolCallDiagnostics.clear();
      canReplay = false;
    }

    if (!toolCallDiagnostics.isEmpty()) {
      canReplay = false;
    }

    if (!hasExplicitCacheBreakdown) {
      this.cacheCreation5m = this.cacheCreationInputTokens;
      this.cacheCreation1h = 0L;
    } else {
      long sum;
      try {
        sum = Math.addExact(this.cacheCreation5m, this.cacheCreation1h);
      } catch (ArithmeticException overflow) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "cache_creation tokens breakdown overflow");
      }
      if (sum != this.cacheCreationInputTokens) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "cache_creation tokens breakdown mismatch");
      }
    }

    ObjectNode usageJsonNode = NODES.objectNode();
    usageJsonNode.put("input_tokens", this.inputTokens);
    usageJsonNode.put("output_tokens", this.outputTokens);
    usageJsonNode.put("cache_read_input_tokens", this.cacheReadInputTokens);
    usageJsonNode.put("cache_creation_input_tokens", this.cacheCreationInputTokens);
    ObjectNode cacheCreationNode = usageJsonNode.putObject("cache_creation");
    cacheCreationNode.put("ephemeral_5m_input_tokens", this.cacheCreation5m);
    cacheCreationNode.put("ephemeral_1h_input_tokens", this.cacheCreation1h);
    if (this.cacheMissReasonType != null) {
      ObjectNode missNode = usageJsonNode.putObject("cache_miss_reason");
      missNode.put("type", this.cacheMissReasonType);
      if (this.cacheMissedInputTokens != null) {
        missNode.put("cache_missed_input_tokens", this.cacheMissedInputTokens);
      }
    }

    String rawUsageJson = usageJsonNode.toString();

    long providerTotalTokens;
    try {
      long sum = Math.addExact(this.inputTokens, this.outputTokens);
      sum = Math.addExact(sum, this.cacheReadInputTokens);
      providerTotalTokens = Math.addExact(sum, this.cacheCreationInputTokens);
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "total tokens arithmetic overflow");
    }

    ModelUsage usage =
        new ModelUsage(
            inputTokens,
            outputTokens,
            cacheReadInputTokens,
            cacheCreation5m,
            cacheCreation1h,
            0L,
            providerTotalTokens);
    ModelCost cost = ModelCost.calculate(request.model().pricing(), usage);

    ProviderResponse response =
        new ProviderResponse(
            combinedText.toString(),
            combinedThinking.toString(),
            Collections.unmodifiableList(toolCalls),
            stopReason,
            usage,
            cost,
            messageId,
            null,
            rawUsageJson,
            Collections.unmodifiableList(toolCallDiagnostics));

    ProviderReplayState replayState = null;
    if (canReplay) {
      ObjectNode payload = NODES.objectNode();
      payload.put("role", "assistant");
      payload.set("content", replayBlocks);
      replayState =
          new ProviderReplayState(
              ProviderReplayFormat.ANTHROPIC_MESSAGES,
              descriptor.affinity(request.model().modelName()),
              frozenSourcePrefixHash,
              payload);
    }

    return new ProviderCompletion(response, replayState);
  }

  private void handleMessageStart(JsonNode root) {
    if (started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected message_start event state");
    }
    started = true;
    JsonNode messageNode = root.get("message");
    if (messageNode == null || !messageNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "message_start missing message object");
    }

    if (messageNode.has("id")) {
      if (!messageNode.get("id").isTextual() || messageNode.get("id").textValue().isBlank()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid message id");
      }
      this.messageId = messageNode.get("id").textValue();
    }

    if (messageNode.has("usage")) {
      JsonNode usageNode = messageNode.get("usage");
      if (!usageNode.isObject()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "usage must be an object");
      }
      applyUsageSnapshot(usageNode);
    }

    if (messageNode.has("diagnostics")) {
      JsonNode diagnosticsNode = messageNode.get("diagnostics");
      if (!diagnosticsNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "diagnostics must be an object");
      }
      if (diagnosticsNode.has("cache_miss_reason")) {
        JsonNode missNode = diagnosticsNode.get("cache_miss_reason");
        if (!missNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "cache_miss_reason must be an object");
        }
        JsonNode typeNode = missNode.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "cache_miss_reason type must be a string");
        }
        String type = typeNode.textValue();
        if (ALLOWED_CACHE_MISS_REASONS.contains(type)) {
          this.cacheMissReasonType = type;
          if (missNode.has("cache_missed_input_tokens")) {
            this.cacheMissedInputTokens =
                parseNonNegativeLong(
                    missNode.get("cache_missed_input_tokens"), "cache_missed_input_tokens");
          }
        }
      }
    }
  }

  private void handleContentBlockStart(JsonNode root) {
    if (!started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_start state");
    }
    JsonNode indexNode = root.get("index");
    if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid content_block_start index");
    }
    int index = indexNode.asInt();
    if (index < 0 || (lastBlockIndex != null && index <= lastBlockIndex)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "duplicate or out-of-order content_block_start index");
    }
    lastBlockIndex = index;

    JsonNode blockNode = root.get("content_block");
    if (blockNode == null || !blockNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content_block_start missing content_block object");
    }
    if (!blockNode.has("type") || !blockNode.get("type").isTextual()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "content_block missing type");
    }

    String type = blockNode.get("type").textValue();
    WireBlockAccumulator accumulator = new WireBlockAccumulator(type);

    switch (type) {
      case "text" -> {
        if (blockNode.has("text")) {
          if (!blockNode.get("text").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "text content_block text must be string");
          }
          String text = blockNode.get("text").textValue();
          if (!text.isEmpty()) {
            accumulator.textBuffer.append(text);
            bridge.emitEvent(new ProviderStreamEvent.TextDelta(text));
          }
        }
      }
      case "thinking" -> {
        if (blockNode.has("thinking")) {
          if (!blockNode.get("thinking").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE,
                "thinking content_block thinking must be string");
          }
          String thinking = blockNode.get("thinking").textValue();
          if (!thinking.isEmpty()) {
            accumulator.thinkingBuffer.append(thinking);
            bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(thinking));
          }
        }
        if (blockNode.has("signature")) {
          if (!blockNode.get("signature").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "thinking signature must be string");
          }
          accumulator.signature = blockNode.get("signature").textValue();
        }
      }
      case "redacted_thinking" -> {
        if (blockNode.has("data")) {
          if (!blockNode.get("data").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "redacted_thinking data must be string");
          }
          accumulator.redactedData = blockNode.get("data").textValue();
        }
      }
      case "tool_use" -> {
        if (!blockNode.has("id")
            || !blockNode.get("id").isTextual()
            || !blockNode.has("name")
            || !blockNode.get("name").isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "tool_use content_block missing valid id or name");
        }
        accumulator.toolId = blockNode.get("id").textValue();
        accumulator.toolName = blockNode.get("name").textValue();
        if (accumulator.toolId.isBlank() || accumulator.toolName.isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "tool_use content_block missing valid id or name");
        }
        accumulator.toolOrdinal = nextToolOrdinal++;
        String initialArguments = null;
        if (blockNode.has("input")) {
          JsonNode inputNode = blockNode.get("input");
          if (!inputNode.isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "tool_use input must be a JSON object");
          }
          if (inputNode.isEmpty()) {
            accumulator.initialInputPlaceholder = true;
          } else {
            initialArguments = inputNode.toString();
            accumulator.toolArgsBuffer.append(initialArguments);
            accumulator.initialArgumentsComplete = true;
          }
        }
        bridge.emitEvent(
            new ProviderStreamEvent.ToolCallDelta(
                accumulator.toolOrdinal,
                accumulator.toolId,
                accumulator.toolName,
                initialArguments));
      }
      default -> {
        // 未知或未来新增 block type，安全忽略
      }
    }

    blocksByIndex.put(index, accumulator);
  }

  private void handleContentBlockDelta(JsonNode root) {
    if (!started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_delta state");
    }
    JsonNode indexNode = root.get("index");
    if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid content_block_delta index");
    }
    int index = indexNode.asInt();
    WireBlockAccumulator block = blocksByIndex.get(index);
    if (block == null || block.state != BlockLifecycle.ACTIVE) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_delta for index: " + index);
    }

    JsonNode deltaNode = root.get("delta");
    if (deltaNode == null || !deltaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content_block_delta missing delta object");
    }
    if (!deltaNode.has("type") || !deltaNode.get("type").isTextual()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "delta object missing type");
    }

    String deltaType = deltaNode.get("type").textValue();
    switch (block.type) {
      case "text" -> {
        if (!"text_delta".equals(deltaType)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for text block");
        }
        if (!deltaNode.has("text") || !deltaNode.get("text").isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "text_delta text must be string");
        }
        String text = deltaNode.get("text").textValue();
        if (!text.isEmpty()) {
          block.textBuffer.append(text);
          bridge.emitEvent(new ProviderStreamEvent.TextDelta(text));
        }
      }
      case "thinking" -> {
        if ("thinking_delta".equals(deltaType)) {
          if (!deltaNode.has("thinking") || !deltaNode.get("thinking").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "thinking_delta thinking must be string");
          }
          String thinking = deltaNode.get("thinking").textValue();
          if (!thinking.isEmpty()) {
            block.thinkingBuffer.append(thinking);
            bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(thinking));
          }
        } else if ("signature_delta".equals(deltaType)) {
          if (!deltaNode.has("signature") || !deltaNode.get("signature").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "signature_delta signature must be string");
          }
          String sig = deltaNode.get("signature").textValue();
          if (block.signature == null) {
            block.signature = sig;
          } else {
            block.signature += sig;
          }
        } else {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for thinking block");
        }
      }
      case "tool_use" -> {
        if (!"input_json_delta".equals(deltaType)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for tool_use block");
        }
        if (block.initialArgumentsComplete) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "tool call arguments already finalized");
        }
        if (!deltaNode.has("partial_json") || !deltaNode.get("partial_json").isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "input_json_delta partial_json must be string");
        }
        String partialJson = deltaNode.get("partial_json").textValue();
        if (!partialJson.isEmpty()) {
          block.toolArgsBuffer.append(partialJson);
          bridge.emitEvent(
              new ProviderStreamEvent.ToolCallDelta(block.toolOrdinal, null, null, partialJson));
        }
      }
      default -> {
        // 未知或不支持 block 忽略其增量
      }
    }
  }

  private void handleContentBlockStop(JsonNode root) {
    if (!started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_stop state");
    }
    JsonNode indexNode = root.get("index");
    if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid content_block_stop index");
    }
    int index = indexNode.asInt();
    WireBlockAccumulator block = blocksByIndex.get(index);
    if (block == null || block.state != BlockLifecycle.ACTIVE) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_stop for index: " + index);
    }
    block.state = BlockLifecycle.STOPPED;
  }

  private void handleMessageDelta(JsonNode root) {
    if (!started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected message_delta state");
    }
    for (WireBlockAccumulator block : blocksByIndex.values()) {
      if (block.state != BlockLifecycle.STOPPED) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "message_delta received while content block active");
      }
    }
    JsonNode deltaNode = root.get("delta");
    if (deltaNode == null || !deltaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "message_delta missing delta object");
    }
    if (deltaNode.has("stop_reason") && !deltaNode.get("stop_reason").isNull()) {
      if (!deltaNode.get("stop_reason").isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "stop_reason must be string");
      }
      String reasonStr = deltaNode.get("stop_reason").textValue();
      this.stopReason = mapStopReason(reasonStr);
    }
    if (root.has("usage")) {
      JsonNode usageNode = root.get("usage");
      if (!usageNode.isObject()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "usage must be an object");
      }
      applyUsageSnapshot(usageNode);
    }
  }

  private void handleMessageStop(JsonNode root) {
    if (!started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected message_stop state");
    }
    if (this.stopReason == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "message_stop received without stop reason");
    }
    for (WireBlockAccumulator block : blocksByIndex.values()) {
      if (block.state != BlockLifecycle.STOPPED) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "message_stop received while content block still active");
      }
    }
    stopped = true;
  }

  private void applyUsageSnapshot(JsonNode usageNode) {
    if (usageNode.has("input_tokens")) {
      this.inputTokens = parseNonNegativeLong(usageNode.get("input_tokens"), "input_tokens");
    }
    if (usageNode.has("output_tokens")) {
      this.outputTokens = parseNonNegativeLong(usageNode.get("output_tokens"), "output_tokens");
    }
    if (usageNode.has("cache_read_input_tokens")) {
      this.cacheReadInputTokens =
          parseNonNegativeLong(usageNode.get("cache_read_input_tokens"), "cache_read_input_tokens");
    }
    if (usageNode.has("cache_creation_input_tokens")) {
      this.cacheCreationInputTokens =
          parseNonNegativeLong(
              usageNode.get("cache_creation_input_tokens"), "cache_creation_input_tokens");
    }
    extractAndValidateCacheCreationBreakdown(usageNode);
  }

  private void extractAndValidateCacheCreationBreakdown(JsonNode usageNode) {
    long m5 = 0L;
    long h1 = 0L;
    boolean has5m = false;
    boolean has1h = false;

    JsonNode nested = usageNode.get("cache_creation");
    if (nested != null) {
      if (!nested.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "cache_creation must be an object");
      }
      if (nested.has("ephemeral_5m_input_tokens")) {
        m5 =
            parseNonNegativeLong(
                nested.get("ephemeral_5m_input_tokens"), "ephemeral_5m_input_tokens");
        has5m = true;
      }
      if (nested.has("ephemeral_5m")) {
        long alias5m =
            parseNonNegativeLong(nested.get("ephemeral_5m"), "ephemeral_5m_input_tokens");
        if (has5m && m5 != alias5m) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "conflicting cache_creation breakdown fields");
        }
        m5 = alias5m;
        has5m = true;
      }
      if (nested.has("ephemeral_1h_input_tokens")) {
        h1 =
            parseNonNegativeLong(
                nested.get("ephemeral_1h_input_tokens"), "ephemeral_1h_input_tokens");
        has1h = true;
      }
      if (nested.has("ephemeral_1h")) {
        long alias1h =
            parseNonNegativeLong(nested.get("ephemeral_1h"), "ephemeral_1h_input_tokens");
        if (has1h && h1 != alias1h) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "conflicting cache_creation breakdown fields");
        }
        h1 = alias1h;
        has1h = true;
      }
    }

    if (usageNode.has("cache_creation_ephemeral_5m_input_tokens")) {
      long flat5m =
          parseNonNegativeLong(
              usageNode.get("cache_creation_ephemeral_5m_input_tokens"),
              "cache_creation_ephemeral_5m_input_tokens");
      if (has5m && m5 != flat5m) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "conflicting cache_creation breakdown fields");
      }
      m5 = flat5m;
      has5m = true;
    }
    if (usageNode.has("cache_creation_ephemeral_1h_input_tokens")) {
      long flat1h =
          parseNonNegativeLong(
              usageNode.get("cache_creation_ephemeral_1h_input_tokens"),
              "cache_creation_ephemeral_1h_input_tokens");
      if (has1h && h1 != flat1h) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "conflicting cache_creation breakdown fields");
      }
      h1 = flat1h;
      has1h = true;
    }

    if (has5m || has1h) {
      if (has5m) {
        this.cacheCreation5m = m5;
      }
      if (has1h) {
        this.cacheCreation1h = h1;
      }
      hasExplicitCacheBreakdown = true;
    }
  }

  private static long parseNonNegativeLong(JsonNode node, String fieldName) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "usage " + fieldName + " must be an integer");
    }
    long val = node.asLong();
    if (val < 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "usage " + fieldName + " must not be negative");
    }
    return val;
  }

  private static GenerationStopReason mapStopReason(String reasonStr) {
    if (reasonStr == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unsupported or missing stop_reason");
    }
    return switch (reasonStr) {
      case "end_turn", "stop_sequence", "tool_use" -> GenerationStopReason.COMPLETE;
      case "max_tokens", "model_context_window_exceeded" -> GenerationStopReason.LENGTH;
      case "refusal" -> GenerationStopReason.FILTERED;
      default -> throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unsupported or missing stop_reason");
    };
  }

  private static JsonNode parseJson(String text) {
    try {
      return OBJECT_MAPPER.readTree(text);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "failed to parse SSE event JSON payload");
    }
  }

  private static JsonNode tryParseJsonObject(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(text);
      return node.isObject() ? node : null;
    } catch (JsonProcessingException exception) {
      return null;
    }
  }

  private enum BlockLifecycle {
    ACTIVE,
    STOPPED
  }

  private static final class WireBlockAccumulator {
    final String type;
    BlockLifecycle state = BlockLifecycle.ACTIVE;

    final StringBuilder textBuffer = new StringBuilder();
    final StringBuilder thinkingBuffer = new StringBuilder();
    String signature = null;
    String redactedData = null;

    int toolOrdinal = -1;
    String toolId = null;
    String toolName = null;
    final StringBuilder toolArgsBuffer = new StringBuilder();
    boolean initialInputPlaceholder = false;
    boolean initialArgumentsComplete = false;

    WireBlockAccumulator(String type) {
      this.type = type;
    }
  }
}
