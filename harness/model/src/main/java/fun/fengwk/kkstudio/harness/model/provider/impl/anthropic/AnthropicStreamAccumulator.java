package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
final class AnthropicStreamAccumulator extends ProviderStreamState {

  private static final Logger log = LoggerFactory.getLogger(AnthropicStreamAccumulator.class);

  private static final Set<String> KNOWN_SSE_EVENTS =
      Set.of(
          "message_start",
          "content_block_start",
          "content_block_delta",
          "content_block_stop",
          "message_delta",
          "message_stop");

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final ProviderStreamBridge bridge;

  private boolean started = false;
  private boolean stopped = false;
  private String messageId = null;

  private final AnthropicContentBlocks blocks;
  private final AnthropicUsageAccumulator usage = new AnthropicUsageAccumulator();

  AnthropicStreamAccumulator(
      ProviderRequest request, ProviderDescriptor descriptor, ProviderStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.blocks = new AnthropicContentBlocks(bridge);
  }

  /** 处理单个 SSE 事件。 */
  void handleEvent(String eventName, String data) {
    if (bridge.isClosed()) {
      return;
    }
    if (eventName == null || eventName.isBlank() || "ping".equals(eventName)) {
      return;
    }
    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
      return;
    }
    if ("error".equals(eventName)) {
      JsonNode node =
          ProviderJsonHelper.parseSseJson(data, "failed to parse SSE event JSON payload");
      throw AnthropicErrorMapper.mapSseErrorEnvelope(node);
    }
    if (!KNOWN_SSE_EVENTS.contains(eventName)) {
      // 安全忽略未知未来事件类型
      return;
    }

    JsonNode node = ProviderJsonHelper.parseSseJson(data, "failed to parse SSE event JSON payload");
    if (!node.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE event data is not a JSON object");
    }
    if (!node.has("type")
        || !node.get("type").isString()
        || !eventName.equals(node.get("type").stringValue())) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "mismatched SSE event type");
    }

    switch (eventName) {
      case "message_start" -> handleMessageStart(node);
      case "content_block_start" -> {
        requireActiveStream("unexpected content_block_start state");
        blocks.start(node);
      }
      case "content_block_delta" -> {
        requireActiveStream("unexpected content_block_delta state");
        blocks.delta(node);
      }
      case "content_block_stop" -> {
        requireActiveStream("unexpected content_block_stop state");
        blocks.stop(node);
      }
      case "message_delta" -> handleMessageDelta(node);
      case "message_stop" -> handleMessageStop(node);
      default -> {}
    }
  }

  /** 在流传输自然结束或接收到完整报文后触发完成组装。 */
  ProviderCompletion finish() {
    if (!usage.hasCompleteUsage()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: Anthropic Messages model " + request.model().modelId());
    }
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
    List<ProviderContentBlock> responseBlocks = new ArrayList<>();
    List<ProviderToolCallDiagnostic> toolCallDiagnostics = new ArrayList<>();

    // blocksByIndex 升序遍历，严格保持原生 block index 顺序
    for (AnthropicWireBlock block : blocks.ordered()) {
      switch (block.type) {
        case "text" -> {
          combinedText.append(block.textBuffer);
          ObjectNode textFields = block.rawBlock.deepCopy();
          if (block.citations != null) {
            textFields.set("citations", block.citations.deepCopy());
          }
          responseBlocks.add(
              new ProviderTextBlock(
                  block.textBuffer.toString(), blockData(textFields, Set.of("text"))));
        }
        case "thinking" -> {
          combinedThinking.append(block.thinkingBuffer);
          ObjectNode thinkingFields = block.rawBlock.deepCopy();
          thinkingFields.put("signature", block.signature);
          responseBlocks.add(
              new ProviderThinkingBlock(
                  block.thinkingBuffer.toString(), blockData(thinkingFields, Set.of("thinking"))));
        }
        case "redacted_thinking" -> {
          if (block.redactedData != null) {
            responseBlocks.add(
                new ProviderRedactedThinkingBlock(
                    block.redactedData, blockData(block.rawBlock, Set.of("data"))));
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
              responseBlocks.add(
                  new ProviderToolCallBlock(
                      call, blockData(block.rawBlock, Set.of("id", "name", "input"))));
            } else {
              if (stopReason == GenerationStopReason.LENGTH) {
                toolCallDiagnostics.add(
                    new ProviderToolCallDiagnostic(
                        block.toolOrdinal,
                        block.toolId,
                        block.toolName,
                        null,
                        "tool call truncated before arguments received"));
              } else {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE, "incomplete tool call missing arguments");
              }
            }
          } else {
            JsonNode parsedArgs = ProviderJsonHelper.tryParseJsonObject(argsStr);
            if (parsedArgs != null) {
              ProviderToolCall call = new ProviderToolCall(block.toolId, block.toolName, argsStr);
              toolCalls.add(call);
              responseBlocks.add(
                  new ProviderToolCallBlock(
                      call, blockData(block.rawBlock, Set.of("id", "name", "input"))));
            } else {
              if (stopReason == GenerationStopReason.LENGTH) {
                toolCallDiagnostics.add(
                    new ProviderToolCallDiagnostic(
                        block.toolOrdinal,
                        block.toolId,
                        block.toolName,
                        argsStr,
                        "tool arguments truncated before valid JSON formed"));
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
            JsonNode parsedInput =
                ProviderJsonHelper.tryParseJsonObject(block.nativeInputBuffer.toString());
            if (parsedInput == null) {
              if (stopReason != GenerationStopReason.LENGTH) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE,
                    "invalid native block input JSON accumulated from input_json_delta");
              }
            } else {
              obj.set("input", parsedInput);
            }
          }
          responseBlocks.add(new ProviderOpaqueBlock(block.type, blockData(obj, Set.of())));
        }
      }
    }

    if (stopReason == GenerationStopReason.FILTERED) {
      toolCalls.clear();
      toolCallDiagnostics.clear();
    }

    AnthropicUsageSnapshot usageSnapshot = usage.freeze();

    ProviderResponse response =
        new ProviderResponse(
            responseBlocks,
            stopReason,
            usageSnapshot.usage(),
            messageId,
            usage.serviceTier(),
            usageSnapshot.rawUsageJson(),
            Collections.unmodifiableList(toolCallDiagnostics),
            null,
            null);

    return new ProviderCompletion(response);
  }

  private ProviderBlockData blockData(ObjectNode rawBlock, Set<String> normalizedFields) {
    ObjectNode nativeFields = rawBlock.deepCopy();
    nativeFields.remove("type");
    for (String field : normalizedFields) {
      nativeFields.remove(field);
    }
    String signature =
        rawBlock.path("signature").isString() ? rawBlock.path("signature").stringValue() : null;
    nativeFields.remove("signature");
    nativeFields.remove("data");
    return new ProviderBlockData(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        descriptor.affinity(request.model().modelId()),
        signature,
        null,
        null,
        nativeFields);
  }

  /** 除 message_start 外的所有事件都只在已开始且未停止的流上处理。 */
  private void requireActiveStream(String message) {
    if (!started || stopped) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, message);
    }
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
      if (!messageNode.get("id").isString() || messageNode.get("id").stringValue().isBlank()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid message id");
      }
      this.messageId = messageNode.get("id").stringValue();
    }

    logInputTransformations(messageNode);

    if (messageNode.has("usage")) {
      JsonNode usageNode = messageNode.get("usage");
      if (!usageNode.isObject()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "usage must be an object");
      }
      usage.applySnapshot(usageNode);
    }

    usage.applyDiagnostics(messageNode.get("diagnostics"));
  }

  private void logInputTransformations(JsonNode messageNode) {
    JsonNode transformations = messageNode.get("input_transformations");
    if (transformations == null || !transformations.isArray()) {
      return;
    }
    for (JsonNode transformation : transformations) {
      String type = stringField(transformation, "type");
      String path = transformation.path("path").toString();
      String reason = stringField(transformation, "reason");
      if ("prefix_binding_mismatch".equals(reason)) {
        log.warn(
            "Anthropic input transformation responseId={} type={} path={} reason={}",
            messageId,
            type,
            path,
            reason);
      } else {
        log.info(
            "Anthropic input transformation responseId={} type={} path={} reason={}",
            messageId,
            type,
            path,
            reason);
      }
    }
  }

  private static String stringField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  private void handleMessageDelta(JsonNode root) {
    if (!started || stopped) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected message_delta state");
    }
    for (AnthropicWireBlock block : blocks.ordered()) {
      if (block.state != AnthropicWireBlock.Lifecycle.STOPPED) {
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
      if (!deltaNode.get("stop_reason").isString()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "stop_reason must be string");
      }
      String reasonStr = deltaNode.get("stop_reason").stringValue();
      this.stopReason = mapStopReason(reasonStr);
    }
    if (root.has("usage")) {
      JsonNode usageNode = root.get("usage");
      if (!usageNode.isObject()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "usage must be an object");
      }
      usage.applySnapshot(usageNode);
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
    for (AnthropicWireBlock block : blocks.ordered()) {
      if (block.state != AnthropicWireBlock.Lifecycle.STOPPED) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "message_stop received while content block still active");
      }
    }
    stopped = true;
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
}
