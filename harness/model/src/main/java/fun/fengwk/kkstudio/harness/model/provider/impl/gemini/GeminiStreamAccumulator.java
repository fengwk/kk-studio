package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Google AI Gemini streamGenerateContent SSE 流式事件状态机与响应累积器。
 *
 * <p>按原生 candidate 0 维护 ordered parts 状态机。官方协议没有定义跨 chunk 的通用 Part 合并算法，因此累积只执行两类可证明安全的文本合并， 其余
 * part 一律作为完整原生边界原样保留：
 *
 * <ul>
 *   <li>同一 slot 的累计快照替换：上游再次发送同一 slot part 的完整累积形态，且形状为超集、双方都无签名、文本严格扩展；
 *   <li>朴素 text/thought part 的增量累积：双方都只含 text/thought，累积不会丢失任何字段语义。
 * </ul>
 *
 * <p>带 thoughtSignature 的 part（包括空文本签名 part）绝不参与字段级合并，签名绝不跨 part 迁移，只有与同一 slot 已记录的保真化 part
 * 完全相等时才视为 可证明的重复；functionCall 与 inlineData / toolResponse 等未知 union 成员同样按完整边界保留。未知字段既不递归合并也不拼接，未知
 * Part 绝不静默丢弃；若某个 part 无法写入合法 replay（同一 data oneof 同时声明 text 与 functionCall），则显式不冻结 replay
 * 而不是交付损坏的原生回放。
 */
final class GeminiStreamAccumulator extends ProviderStreamState {

  private static final Set<String> FILTERED_FINISH_REASONS =
      Set.of(
          "SAFETY",
          "RECITATION",
          "PROHIBITED_CONTENT",
          "SPII",
          "BLOCKLIST",
          "IMAGE_SAFETY",
          "IMAGE_RECITATION",
          "IMAGE_PROHIBITED_CONTENT",
          "LANGUAGE",
          "ESCALATION");

  private static final Set<String> MALFORMED_OR_ERROR_FINISH_REASONS =
      Set.of(
          "MALFORMED_FUNCTION_CALL",
          "MALFORMED_RESPONSE",
          "MISSING_THOUGHT_SIGNATURE",
          "UNEXPECTED_TOOL_CALL",
          "TOO_MANY_TOOL_CALLS",
          "OTHER",
          "IMAGE_OTHER",
          "NO_IMAGE");

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final ProviderStreamBridge bridge;

  private final GeminiPartAccumulator parts;

  private boolean terminalChunkReceived = false;
  private boolean promptBlocked = false;

  private final GeminiUsageAccumulator usage = new GeminiUsageAccumulator();

  GeminiStreamAccumulator(
      ProviderRequest request, ProviderDescriptor descriptor, ProviderStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.parts = new GeminiPartAccumulator(bridge);
  }

  /** 处理单个 SSE 事件。 */
  void handleEvent(String eventName, String data) {
    if (bridge.isClosed()) {
      return;
    }
    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
      return;
    }

    JsonNode root = ProviderJsonHelper.parseSseJson(data, "malformed SSE data JSON");

    // 检查是否有顶级错误信封
    if (root.has("error") && root.get("error").isObject()) {
      throw GeminiErrorMapper.mapSseErrorEnvelope(root);
    }

    // 检查 promptFeedback
    if (root.has("promptFeedback") && root.get("promptFeedback").isObject()) {
      JsonNode promptFeedback = root.get("promptFeedback");
      if (promptFeedback.has("blockReason") && !promptFeedback.get("blockReason").isNull()) {
        this.promptBlocked = true;
      }
    }

    // 处理 usageMetadata
    if (root.has("usageMetadata")) {
      if (!root.get("usageMetadata").isObject()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid usageMetadata");
      }
      usage.update(root.get("usageMetadata"));
    }

    // 处理 candidates
    if (root.has("candidates") && root.get("candidates").isArray()) {
      ArrayNode candidates = (ArrayNode) root.get("candidates");
      if (candidates.size() > 0) {
        JsonNode firstCandidate = candidates.get(0);
        processCandidate(firstCandidate);
      }
    }
  }

  private void processCandidate(JsonNode candidate) {
    boolean hasContent = candidate.has("content") && candidate.get("content").isObject();
    boolean hasFinishReason =
        candidate.has("finishReason")
            && !candidate.get("finishReason").isNull()
            && !candidate.get("finishReason").asString("").isBlank();
    if (terminalChunkReceived && (hasContent || hasFinishReason)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content received after Gemini terminal chunk");
    }
    // 检查 finishReason
    if (candidate.has("finishReason")
        && !candidate.get("finishReason").isNull()
        && !candidate.get("finishReason").asString("").isBlank()) {
      String rawFinishReason = candidate.get("finishReason").asString("").trim();
      if (!"FINISH_REASON_UNSPECIFIED".equalsIgnoreCase(rawFinishReason)) {
        this.stopReason = mapFinishReason(rawFinishReason);
        this.terminalChunkReceived = true;
      }
    }

    // 处理 content.parts
    if (candidate.has("content") && candidate.get("content").isObject()) {
      JsonNode contentNode = candidate.get("content");
      if (contentNode.has("parts") && contentNode.get("parts").isArray()) {
        ArrayNode partsArray = (ArrayNode) contentNode.get("parts");
        parts.process(partsArray);
      }
    }
  }

  private GenerationStopReason mapFinishReason(String finishReason) {
    String upper = finishReason.toUpperCase(Locale.ROOT);
    if ("STOP".equals(upper)) {
      return GenerationStopReason.COMPLETE;
    }
    if ("MAX_TOKENS".equals(upper)) {
      return GenerationStopReason.LENGTH;
    }
    if (FILTERED_FINISH_REASONS.contains(upper)) {
      return GenerationStopReason.FILTERED;
    }
    if (MALFORMED_OR_ERROR_FINISH_REASONS.contains(upper)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "Gemini abnormal finish reason: " + finishReason);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_RESPONSE, "unsupported Gemini finish reason: " + finishReason);
  }

  /** 流正常传输完毕后的终态验证与 Completion 交付。 */
  ProviderCompletion finish() {
    if (!usage.hasSnapshot()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: Gemini model " + request.model().modelId());
    }
    GeminiUsageSnapshot usageSnapshot = usage.freeze();
    requireTerminalStopReason();

    List<ProviderToolCall> effectiveToolCalls;
    if (this.stopReason == GenerationStopReason.FILTERED) {
      effectiveToolCalls = List.of();
    } else {
      effectiveToolCalls =
          Collections.unmodifiableList(new ArrayList<>(parts.collectedToolCalls()));
    }

    ProviderResponse response =
        new ProviderResponse(
            responseBlocks(effectiveToolCalls),
            this.stopReason,
            usageSnapshot.usage(),
            null,
            usageSnapshot.serviceTier(),
            usageSnapshot.rawUsageJson(),
            List.of(),
            null,
            null);

    return new ProviderCompletion(response);
  }

  /** 只有 prompt 被拦截且没有任何 part 时才把缺失的终止原因解释为 FILTERED。 */
  private void requireTerminalStopReason() {
    if (this.stopReason != null) {
      return;
    }
    if (this.promptBlocked && parts.trackedParts().isEmpty()) {
      this.stopReason = GenerationStopReason.FILTERED;
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_RESPONSE,
        "Gemini stream completed without terminal finish reason");
  }

  /** 按 part 顺序投影 normalized block；FILTERED 时撤回工具调用。 */
  private List<ProviderContentBlock> responseBlocks(List<ProviderToolCall> effectiveToolCalls) {
    List<ProviderContentBlock> responseBlocks = new ArrayList<>();
    int toolCallIndex = 0;
    for (TrackedPart part : parts.trackedParts()) {
      boolean hasSemanticText = part.hasText || part.thought;
      boolean replayablePart = !(part.hasText && part.isFunctionCall());
      if (hasSemanticText) {
        ProviderBlockData textData =
            replayablePart ? blockData(part.rawPart, Set.of("text", "thought")) : null;
        if (part.thought) {
          responseBlocks.add(new ProviderThinkingBlock(part.text.toString(), textData));
        } else {
          responseBlocks.add(new ProviderTextBlock(part.text.toString(), textData));
        }
      }
      if (part.isFunctionCall()) {
        if (this.stopReason != GenerationStopReason.FILTERED) {
          responseBlocks.add(
              new ProviderToolCallBlock(
                  effectiveToolCalls.get(toolCallIndex++),
                  replayablePart ? functionCallBlockData(part.rawPart) : null));
        }
      } else if (!hasSemanticText) {
        responseBlocks.add(
            new ProviderOpaqueBlock("gemini_part", blockData(part.rawPart, Set.of())));
      }
    }
    return responseBlocks;
  }

  private ProviderBlockData blockData(ObjectNode rawPart, Set<String> semanticFields) {
    ObjectNode nativeFields = rawPart.deepCopy();
    for (String field : semanticFields) {
      nativeFields.remove(field);
    }
    String signature =
        rawPart.path("thoughtSignature").isString()
            ? rawPart.path("thoughtSignature").stringValue()
            : null;
    nativeFields.remove("thoughtSignature");
    Boolean thought =
        rawPart.path("thought").isBoolean() ? rawPart.path("thought").booleanValue() : null;
    nativeFields.remove("thought");
    return new ProviderBlockData(
        ProviderReplayFormat.GEMINI_CONTENT,
        descriptor.affinity(request.model().modelId()),
        signature,
        null,
        null,
        null,
        null,
        false,
        thought,
        nativeFields);
  }

  private ProviderBlockData functionCallBlockData(ObjectNode rawPart) {
    ObjectNode nativeFields = rawPart.deepCopy();
    nativeFields.remove("functionCall");
    nativeFields.remove("text");
    nativeFields.remove("thought");
    nativeFields.remove("thoughtSignature");
    JsonNode functionCall = rawPart.get("functionCall");
    if (functionCall != null && functionCall.isObject()) {
      ObjectNode functionCallFields = ((ObjectNode) functionCall).deepCopy();
      functionCallFields.remove("name");
      functionCallFields.remove("id");
      functionCallFields.remove("args");
      if (!functionCallFields.isEmpty()) {
        nativeFields.set("functionCall", functionCallFields);
      }
    }
    String signature =
        rawPart.path("thoughtSignature").isString()
            ? rawPart.path("thoughtSignature").stringValue()
            : null;
    return new ProviderBlockData(
        ProviderReplayFormat.GEMINI_CONTENT,
        descriptor.affinity(request.model().modelId()),
        signature,
        null,
        null,
        null,
        null,
        false,
        null,
        nativeFields);
  }
}
