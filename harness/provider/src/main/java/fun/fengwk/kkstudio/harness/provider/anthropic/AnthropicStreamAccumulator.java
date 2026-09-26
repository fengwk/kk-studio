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
import java.util.Iterator;
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
 *
 * <p>每个 block 的 {@code content_block_start} 对象都是 replay 事实源：终态只覆盖 normalized
 * 字段（text、thinking/signature、 redacted data、tool input、citations），官方附加字段原样保留。官方 delta 只有 {@code
 * text_delta}、{@code input_json_delta}、{@code thinking_delta}、{@code signature_delta}、{@code
 * citations_delta}：只接受可证明安全的 block/delta 配对，未知配对显式失败而不是猜测性合并。<br>
 * 未知但合法的 provider block（如 server tool use）本身原样保留，携带 {@code input_json_delta} 时按 deterministic 规则累积
 * partial JSON 并在终态解析为 {@code input} 对象。
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

  /** usage 快照的原始累积：按顶层字段合并，保留官方未知字段；normalized 计数在终态覆盖同名键。 */
  private final ObjectNode rawUsage = NODES.objectNode();

  private String serviceTier = null;

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

    // CONTINUE（pause_turn / compaction）同样是「下一请求必须原生回放上游字段」的终止态
    boolean canReplay =
        (stopReason == GenerationStopReason.COMPLETE
            || stopReason == GenerationStopReason.LENGTH
            || stopReason == GenerationStopReason.CONTINUE);
    ArrayNode replayBlocks = NODES.arrayNode();

    // blocksByIndex 升序遍历，严格保持原生 block index 顺序
    for (WireBlockAccumulator block : blocksByIndex.values()) {
      switch (block.type) {
        case "text" -> {
          combinedText.append(block.textBuffer);
          if (canReplay) {
            // start 对象是 replay 基座：只覆盖 normalized 文本，citations 等官方附加字段原样保留
            ObjectNode obj = block.rawBlock.deepCopy();
            obj.put("text", block.textBuffer.toString());
            if (block.citations != null) {
              obj.set("citations", block.citations.deepCopy());
            }
            replayBlocks.add(obj);
          }
        }
        case "thinking" -> {
          combinedThinking.append(block.thinkingBuffer);
          if (block.signature == null || block.signature.isBlank()) {
            canReplay = false;
          } else if (canReplay) {
            ObjectNode obj = block.rawBlock.deepCopy();
            obj.put("thinking", block.thinkingBuffer.toString());
            obj.put("signature", block.signature);
            replayBlocks.add(obj);
          }
        }
        case "redacted_thinking" -> {
          if (block.redactedData == null || block.redactedData.isBlank()) {
            canReplay = false;
          } else if (canReplay) {
            ObjectNode obj = block.rawBlock.deepCopy();
            obj.put("data", block.redactedData);
            replayBlocks.add(obj);
          }
        }
        case "tool_use" -> {
          if (stopReason == GenerationStopReason.FILTERED) {
            // FILTERED 协议撤回，忽略工具处理
            break;
          }
          if (stopReason == GenerationStopReason.CONTINUE) {
            // CONTINUE 是撤下工具意图的续写终止态：归一化 tool call 与 native-only 续写语义不可共存，显式失败
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE,
                "CONTINUE response must not contain tool calls");
          }
          String argsStr = block.toolArgsBuffer.toString();
          if (argsStr.isEmpty()) {
            if (block.initialInputPlaceholder) {
              // 终态补齐 {}
              ProviderToolCall call = new ProviderToolCall(block.toolId, block.toolName, "{}");
              toolCalls.add(call);
              if (canReplay) {
                ObjectNode obj = block.rawBlock.deepCopy();
                obj.putObject("input");
                replayBlocks.add(obj);
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
                ObjectNode obj = block.rawBlock.deepCopy();
                obj.set("input", parsedArgs);
                replayBlocks.add(obj);
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
        default -> {
          ObjectNode obj = block.rawBlock.deepCopy();
          if (block.nativeInputBuffer != null) {
            JsonNode parsedInput = tryParseJsonObject(block.nativeInputBuffer.toString());
            if (parsedInput == null) {
              if (stopReason == GenerationStopReason.LENGTH) {
                // 截断的 native tool 输入无法组成合法对象：保留余下 normalized 语义，但不冻结 replay
                canReplay = false;
              } else {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE,
                    "invalid native block input JSON accumulated from input_json_delta");
              }
            } else {
              obj.set("input", parsedInput);
            }
          }
          if (canReplay) {
            // 未知但合法的 provider block：opaque 原样保留，只覆盖由 delta 累积出的 input
            replayBlocks.add(obj);
          }
        }
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

    // usage 以原始快照的顶层合并结果为基座：官方未知字段（如 server_tool_use）原样保留，normalized 计数覆盖同名键
    ObjectNode usageJsonNode = rawUsage;
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

    // Anthropic Messages 无原生 total_tokens，不合成虚假总数，严格保留原生语义为 0
    long providerTotalTokens = 0L;

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
            serviceTier,
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
              descriptor.affinity(request.model().modelId()),
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
    // start 对象是所有 block 的 replay 基座：终态只覆盖 normalized 字段，官方附加字段原样保留
    WireBlockAccumulator accumulator =
        new WireBlockAccumulator(type, (ObjectNode) blockNode.deepCopy());

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
        if (blockNode.has("citations")) {
          // start 已声明 citations 时，后续 citations_delta 依序追加在其后
          accumulator.citations = requireCitationArray(blockNode.get("citations"));
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
        // 未知或未来新增 block type：不产生 normalized 语义，raw block 原样保留供 opaque replay
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
        switch (deltaType) {
          case "text_delta" -> {
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
          case "citations_delta" -> {
            if (!deltaNode.has("citation") || !deltaNode.get("citation").isObject()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_RESPONSE, "citations_delta citation must be an object");
            }
            if (block.citations == null) {
              block.citations = NODES.arrayNode();
            }
            // citation 是官方可扩展结构：原样依序累积，不做字段白名单
            block.citations.add(deltaNode.get("citation").deepCopy());
          }
          default -> throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for text block");
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
        String partialJson =
            requirePartialJson(deltaNode, "input_json_delta partial_json must be string");
        if (!partialJson.isEmpty()) {
          block.toolArgsBuffer.append(partialJson);
          bridge.emitEvent(
              new ProviderStreamEvent.ToolCallDelta(block.toolOrdinal, null, null, partialJson));
        }
      }
      case "redacted_thinking" -> throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "redacted_thinking block must be complete and carries no delta");
      default -> handleNativeBlockDelta(block, deltaType, deltaNode);
    }
  }

  /**
   * 原生（非 normalized）block 的唯一可安全组装 delta 是 {@code input_json_delta}：partial JSON 依序累积，终态解析为 {@code
   * input} 对象。其余 delta 无法证明可保真组装，显式失败而不是猜测性合并或静默丢弃。
   */
  private static void handleNativeBlockDelta(
      WireBlockAccumulator block, String deltaType, JsonNode deltaNode) {
    if (!"input_json_delta".equals(deltaType)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "unsupported delta type for native block: " + deltaType);
    }
    if (block.rawBlock.has("input")) {
      JsonNode declaredInput = block.rawBlock.get("input");
      if (!declaredInput.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "native block input must be a JSON object");
      }
      // 空对象占位可被 delta 累积结果替换；非空 input 无法与 delta 无损合并
      if (!declaredInput.isEmpty()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "native block input_json_delta conflicts with non-empty input already declared");
      }
    }
    String partialJson =
        requirePartialJson(deltaNode, "input_json_delta partial_json must be string");
    if (block.nativeInputBuffer == null) {
      block.nativeInputBuffer = new StringBuilder();
    }
    block.nativeInputBuffer.append(partialJson);
  }

  private static String requirePartialJson(JsonNode deltaNode, String errorMessage) {
    if (!deltaNode.has("partial_json") || !deltaNode.get("partial_json").isTextual()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, errorMessage);
    }
    return deltaNode.get("partial_json").textValue();
  }

  /** citation 集合只校验容器与元素形状，官方未来新增的 citation 字段一律无损保留。 */
  private static ArrayNode requireCitationArray(JsonNode citationsNode) {
    if (!citationsNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content_block citations must be an array");
    }
    ArrayNode citations = NODES.arrayNode();
    for (JsonNode citation : citationsNode) {
      if (!citation.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "content_block citations must contain objects");
      }
      citations.add(citation.deepCopy());
    }
    return citations;
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
    // 原始快照按顶层字段合并：官方未知字段（如 server_tool_use）原样保留，绝不被 normalized 解析丢弃
    Iterator<Map.Entry<String, JsonNode>> rawFields = usageNode.fields();
    while (rawFields.hasNext()) {
      Map.Entry<String, JsonNode> field = rawFields.next();
      rawUsage.set(field.getKey(), field.getValue().deepCopy());
    }

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
    if (usageNode.has("service_tier") && !usageNode.get("service_tier").isNull()) {
      JsonNode tierNode = usageNode.get("service_tier");
      if (!tierNode.isTextual() || tierNode.textValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "usage service_tier must be a non-blank string");
      }
      this.serviceTier = tierNode.textValue();
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
        // pause_turn（server tool 仍在运行）与 compaction（历史已被摘要）都要求立即用原生 replay 续写
      case "pause_turn", "compaction" -> GenerationStopReason.CONTINUE;
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

    /** provider 给出的原始 start 对象：所有 block 的 replay 基座，终态只覆盖 normalized 字段。 */
    final ObjectNode rawBlock;

    BlockLifecycle state = BlockLifecycle.ACTIVE;

    final StringBuilder textBuffer = new StringBuilder();

    /** text block 累积到的 citation 列表；null 表示上游未下发 citations_delta（start 自带的 citations 原样保留）。 */
    ArrayNode citations = null;

    final StringBuilder thinkingBuffer = new StringBuilder();
    String signature = null;
    String redactedData = null;

    int toolOrdinal = -1;
    String toolId = null;
    String toolName = null;
    final StringBuilder toolArgsBuffer = new StringBuilder();
    boolean initialInputPlaceholder = false;
    boolean initialArgumentsComplete = false;

    /** 原生（非 normalized）block 累积到的 input_json_delta partial JSON；null 表示上游未下达该类 delta。 */
    StringBuilder nativeInputBuffer = null;

    WireBlockAccumulator(String type, ObjectNode rawBlock) {
      this.type = type;
      this.rawBlock = rawBlock;
    }
  }
}
