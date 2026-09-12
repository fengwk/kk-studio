package fun.fengwk.kkstudio.harness.provider.gemini;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Google AI Gemini streamGenerateContent SSE 流式事件状态机与响应累积器。
 *
 * <p>按原生 candidate 0 维护 ordered parts 状态机，确定性区分累计快照与增量 parts 去重， 支持 thought/thoughtSignature
 * 精确保留与白名单 terminal replay 隔离。
 */
final class GeminiStreamAccumulator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

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
          "LANGUAGE");

  private static final Set<String> MALFORMED_OR_ERROR_FINISH_REASONS =
      Set.of(
          "MALFORMED_FUNCTION_CALL",
          "MALFORMED_RESPONSE",
          "MISSING_THOUGHT_SIGNATURE",
          "UNEXPECTED_TOOL_CALL",
          "TOO_MANY_TOOL_CALLS",
          "OTHER",
          "IMAGE_OTHER",
          "NO_IMAGE",
          "ESCALATION");

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final String frozenSourcePrefixHash;
  private final GeminiStreamBridge bridge;

  private final List<TrackedPart> trackedParts = new ArrayList<>();
  private final List<ProviderToolCall> collectedToolCalls = new ArrayList<>();
  private int nextToolOrdinal = 0;

  private GenerationStopReason stopReason = null;
  private boolean terminalChunkReceived = false;
  private boolean promptBlocked = false;

  private ModelUsage latestUsage = null;
  private String rawUsageJson = "{}";

  GeminiStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      String frozenSourcePrefixHash,
      GeminiStreamBridge bridge) {
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
    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
      return;
    }

    JsonNode root = parseJson(data);

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
    if (root.has("usageMetadata") && root.get("usageMetadata").isObject()) {
      updateUsage(root.get("usageMetadata"));
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

  private void updateUsage(JsonNode usageNode) {
    int promptTokens = usageNode.path("promptTokenCount").asInt(0);
    int candidatesTokens = usageNode.path("candidatesTokenCount").asInt(0);
    int totalTokens = usageNode.path("totalTokenCount").asInt(0);
    int cachedTokens = usageNode.path("cachedContentTokenCount").asInt(0);
    int thoughtsTokens = usageNode.path("thoughtsTokenCount").asInt(0);

    long cached = Math.max(0L, cachedTokens);
    long ordinaryInput = Math.max(0L, (long) promptTokens - cached);
    long output = Math.max(0L, candidatesTokens);
    long reasoning = Math.max(0L, thoughtsTokens);
    long providerTotal = Math.max(0L, totalTokens);

    this.latestUsage =
        new ModelUsage(ordinaryInput, output, cached, 0L, 0L, reasoning, providerTotal);

    // 白名单 rawUsageJson
    ObjectNode whiteNode = NODES.objectNode();
    if (usageNode.has("promptTokenCount")) {
      whiteNode.put("promptTokenCount", promptTokens);
    }
    if (usageNode.has("candidatesTokenCount")) {
      whiteNode.put("candidatesTokenCount", candidatesTokens);
    }
    if (usageNode.has("totalTokenCount")) {
      whiteNode.put("totalTokenCount", totalTokens);
    }
    if (usageNode.has("cachedContentTokenCount")) {
      whiteNode.put("cachedContentTokenCount", cachedTokens);
    }
    if (usageNode.has("thoughtsTokenCount")) {
      whiteNode.put("thoughtsTokenCount", thoughtsTokens);
    }
    this.rawUsageJson = whiteNode.toString();
  }

  private void processCandidate(JsonNode candidate) {
    // 检查 finishReason
    if (candidate.has("finishReason")
        && !candidate.get("finishReason").isNull()
        && !candidate.get("finishReason").asText().isBlank()) {
      String rawFinishReason = candidate.get("finishReason").asText().trim();
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
        processParts(partsArray);
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

  private void processParts(ArrayNode partsArray) {
    if (partsArray.size() == 0) {
      return;
    }

    validateParts(partsArray);

    boolean isCumulativeSnapshot = checkCumulativeSnapshot(partsArray);

    if (isCumulativeSnapshot) {
      for (int i = 0; i < partsArray.size(); i++) {
        JsonNode partNode = partsArray.get(i);
        if (i < trackedParts.size()) {
          TrackedPart tp = trackedParts.get(i);
          updateExistingTrackedPart(tp, partNode);
        } else {
          TrackedPart newPart = createAndEmitNewPart(partNode);
          trackedParts.add(newPart);
        }
      }
    } else {
      for (JsonNode partNode : partsArray) {
        boolean isThought = partNode.path("thought").asBoolean(false);
        boolean hasThoughtSignature =
            partNode.has("thoughtSignature") && !partNode.get("thoughtSignature").isNull();
        boolean hasText = partNode.has("text") && !partNode.get("text").isNull();
        boolean hasFunctionCall = partNode.has("functionCall");

        if (hasText
            && !isThought
            && !hasThoughtSignature
            && !hasFunctionCall
            && !trackedParts.isEmpty()
            && trackedParts.get(trackedParts.size() - 1).isOrdinaryText()) {
          TrackedPart lastPart = trackedParts.get(trackedParts.size() - 1);
          String delta = partNode.get("text").asText("");
          if (!delta.isEmpty()) {
            lastPart.textBuilder.append(delta);
            bridge.emitEvent(new ProviderStreamEvent.TextDelta(delta));
          }
        } else {
          TrackedPart newPart = createAndEmitNewPart(partNode);
          trackedParts.add(newPart);
        }
      }
    }
  }

  private void validateParts(ArrayNode partsArray) {
    for (JsonNode partNode : partsArray) {
      if (partNode == null || !partNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "candidate part must be a JSON object");
      }
      if (partNode.has("functionCall")) {
        JsonNode fnNode = partNode.get("functionCall");
        if (fnNode == null || fnNode.isNull() || !fnNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "functionCall must be a JSON object");
        }
        if (!fnNode.has("name")
            || fnNode.get("name") == null
            || !fnNode.get("name").isTextual()
            || fnNode.get("name").asText().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "functionCall name must be a non-blank string");
        }
        if (!fnNode.has("args")
            || fnNode.get("args") == null
            || fnNode.get("args").isNull()
            || !fnNode.get("args").isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "functionCall args must be an explicit JSON object");
        }
        if (fnNode.has("id") && fnNode.get("id") != null && !fnNode.get("id").isNull()) {
          if (!fnNode.get("id").isTextual() || fnNode.get("id").asText().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "functionCall id must be a non-blank string");
          }
        }
      }
    }
  }

  private boolean checkCumulativeSnapshot(ArrayNode partsArray) {
    if (trackedParts.isEmpty()) {
      return false;
    }
    JsonNode firstIncoming = partsArray.get(0);
    TrackedPart firstTracked = trackedParts.get(0);

    if (firstIncoming.has("text") && !firstIncoming.get("text").isNull()) {
      String incomingText = firstIncoming.get("text").asText();
      String trackedText = firstTracked.textBuilder.toString();
      boolean incomingIsThought = firstIncoming.path("thought").asBoolean(false);

      if (incomingIsThought == firstTracked.isThought) {
        if (incomingText.startsWith(trackedText) && incomingText.length() >= trackedText.length()) {
          return true;
        }
      }
    } else if (firstIncoming.has("functionCall") && firstTracked.isFunctionCall()) {
      String incomingFnName = firstIncoming.path("functionCall").path("name").asText("");
      if (incomingFnName.equals(firstTracked.functionCallName)) {
        return true;
      }
    }

    return false;
  }

  private void updateExistingTrackedPart(TrackedPart tp, JsonNode partNode) {
    if (partNode.has("text") && !partNode.get("text").isNull()) {
      String currentText = partNode.get("text").asText();
      String prevText = tp.textBuilder.toString();
      if (currentText.startsWith(prevText)) {
        if (currentText.length() > prevText.length()) {
          String delta = currentText.substring(prevText.length());
          tp.textBuilder.append(delta);
          if (tp.isThought) {
            bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(delta));
          } else {
            bridge.emitEvent(new ProviderStreamEvent.TextDelta(delta));
          }
        }
      }
    }

    if (partNode.has("thoughtSignature") && !partNode.get("thoughtSignature").isNull()) {
      tp.thoughtSignature = partNode.get("thoughtSignature").asText();
    }

    if (partNode.has("functionCall") && tp.isFunctionCall() && !tp.functionCallEmitted) {
      emitFunctionCall(tp);
    }
  }

  private TrackedPart createAndEmitNewPart(JsonNode partNode) {
    TrackedPart tp = new TrackedPart();

    if (partNode.has("thoughtSignature") && !partNode.get("thoughtSignature").isNull()) {
      tp.thoughtSignature = partNode.get("thoughtSignature").asText();
    }

    if (partNode.has("thought") && partNode.get("thought").asBoolean(false)) {
      tp.isThought = true;
    }

    if (partNode.has("text") && !partNode.get("text").isNull()) {
      String text = partNode.get("text").asText();
      tp.textBuilder.append(text);
      if (!text.isEmpty()) {
        if (tp.isThought) {
          bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(text));
        } else {
          bridge.emitEvent(new ProviderStreamEvent.TextDelta(text));
        }
      }
    }

    if (partNode.has("functionCall")) {
      JsonNode fnNode = partNode.get("functionCall");
      tp.functionCallName = fnNode.get("name").asText();
      String id =
          (fnNode.has("id") && !fnNode.get("id").isNull())
              ? fnNode.get("id").asText()
              : "call_" + nextToolOrdinal;
      tp.functionCallId = id;
      tp.functionCallArgs = fnNode.get("args");
      emitFunctionCall(tp);
    }

    return tp;
  }

  private void emitFunctionCall(TrackedPart tp) {
    if (tp.functionCallEmitted) {
      return;
    }
    if (tp.functionCallId == null
        || tp.functionCallId.isBlank()
        || tp.functionCallName == null
        || tp.functionCallName.isBlank()
        || tp.functionCallArgs == null
        || !tp.functionCallArgs.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid function call in stream emission");
    }
    tp.functionCallEmitted = true;
    String argsJson = tp.functionCallArgs.toString();
    ProviderToolCall toolCall =
        new ProviderToolCall(tp.functionCallId, tp.functionCallName, argsJson);
    collectedToolCalls.add(toolCall);

    bridge.emitEvent(
        new ProviderStreamEvent.ToolCallDelta(
            nextToolOrdinal, tp.functionCallId, tp.functionCallName, argsJson));
    nextToolOrdinal++;
  }

  /** 流正常传输完毕后的终态验证与 Completion 交付。 */
  ProviderCompletion finish() {
    if (this.stopReason == null) {
      if (this.promptBlocked && trackedParts.isEmpty()) {
        this.stopReason = GenerationStopReason.FILTERED;
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "Gemini stream completed without terminal finish reason");
      }
    }

    List<ProviderToolCall> effectiveToolCalls;
    if (this.stopReason == GenerationStopReason.FILTERED) {
      effectiveToolCalls = List.of();
    } else {
      effectiveToolCalls = Collections.unmodifiableList(new ArrayList<>(collectedToolCalls));
    }

    StringBuilder fullText = new StringBuilder();
    StringBuilder fullThinking = new StringBuilder();

    for (TrackedPart tp : trackedParts) {
      if (tp.isThought) {
        fullThinking.append(tp.textBuilder);
      } else if (!tp.isFunctionCall()) {
        fullText.append(tp.textBuilder);
      }
    }

    ModelUsage usage =
        this.latestUsage != null ? this.latestUsage : new ModelUsage(0L, 0L, 0L, 0L, 0L, 0L, 0L);

    ModelCost cost = ModelCost.calculate(request.model().pricing(), usage);

    ProviderResponse response =
        new ProviderResponse(
            fullText.toString(),
            fullThinking.toString(),
            effectiveToolCalls,
            this.stopReason,
            usage,
            cost,
            null,
            null,
            this.rawUsageJson);

    ProviderReplayState replayState = null;
    if (this.stopReason == GenerationStopReason.COMPLETE) {
      replayState = buildReplayState();
    }

    return new ProviderCompletion(response, replayState);
  }

  private ProviderReplayState buildReplayState() {
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "model");
    ArrayNode partsArray = payload.putArray("parts");

    for (TrackedPart tp : trackedParts) {
      ObjectNode partNode = partsArray.addObject();
      if (tp.isFunctionCall()) {
        if (tp.functionCallName == null
            || tp.functionCallName.isBlank()
            || tp.functionCallArgs == null
            || !tp.functionCallArgs.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "invalid function call in replay construction");
        }
        ObjectNode fnNode = partNode.putObject("functionCall");
        fnNode.put("name", tp.functionCallName);
        if (tp.functionCallId != null && !tp.functionCallId.isBlank()) {
          fnNode.put("id", tp.functionCallId);
        }
        fnNode.set("args", tp.functionCallArgs);
        if (tp.thoughtSignature != null && !tp.thoughtSignature.isBlank()) {
          partNode.put("thoughtSignature", tp.thoughtSignature);
        }
      } else {
        partNode.put("text", tp.textBuilder.toString());
        if (tp.isThought) {
          partNode.put("thought", true);
        }
        if (tp.thoughtSignature != null && !tp.thoughtSignature.isBlank()) {
          partNode.put("thoughtSignature", tp.thoughtSignature);
        }
      }
    }

    return new ProviderReplayState(
        ProviderReplayFormat.GEMINI_CONTENT,
        descriptor.affinity(request.model().modelId()),
        frozenSourcePrefixHash,
        payload);
  }

  private static JsonNode parseJson(String data) {
    try {
      return OBJECT_MAPPER.readTree(data);
    } catch (JsonProcessingException e) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "malformed SSE data JSON");
    }
  }

  private static final class TrackedPart {
    boolean isThought = false;
    final StringBuilder textBuilder = new StringBuilder();
    String thoughtSignature = null;

    String functionCallName = null;
    String functionCallId = null;
    JsonNode functionCallArgs = null;
    boolean functionCallEmitted = false;

    boolean isFunctionCall() {
      return functionCallName != null;
    }

    boolean isOrdinaryText() {
      return !isThought
          && !isFunctionCall()
          && (thoughtSignature == null || thoughtSignature.isBlank());
    }
  }
}
