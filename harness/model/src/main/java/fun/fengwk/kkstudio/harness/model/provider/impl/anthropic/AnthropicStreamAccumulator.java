package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamState;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Anthropic Messages SSE 流式事件状态机与响应累积器。
 *
 * <p>本类只负责事件级校验与分派：block 状态机在 {@link AnthropicContentBlocks}，usage 累积在 {@link
 * AnthropicUsageAccumulator}，终态装配在 {@link AnthropicResponseAssembler}。
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
  private final AnthropicResponseAssembler responseAssembler;

  AnthropicStreamAccumulator(
      ProviderRequest request, ProviderDescriptor descriptor, ProviderStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.blocks = new AnthropicContentBlocks(bridge);
    this.responseAssembler = new AnthropicResponseAssembler(request, descriptor);
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

    AnthropicAssembledResponse assembled = responseAssembler.assemble(blocks.ordered(), stopReason);
    AnthropicUsageSnapshot usageSnapshot = usage.freeze();

    ProviderResponse response =
        new ProviderResponse(
            assembled.blocks(),
            stopReason,
            usageSnapshot.usage(),
            messageId,
            usage.serviceTier(),
            usageSnapshot.rawUsageJson(),
            Collections.unmodifiableList(assembled.toolCallDiagnostics()),
            null);

    return new ProviderCompletion(response);
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
    if (!blocks.allStopped()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "message_delta received while content block active");
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
    if (!blocks.allStopped()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "message_stop received while content block still active");
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
