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
import java.util.Iterator;
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

  /** 只含 text/thought 的朴素 part：仅这种形状允许增量文本累积。 */
  private static final Set<String> PLAIN_PART_FIELDS = Set.of("text", "thought");

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

  /** 累积状态是否可安全写入原生 replay；无法保真的 union 冲突会显式关闭冻结，而不是交付损坏的回放。 */
  private boolean replayFreezable = true;

  private ModelUsage latestUsage = null;
  private String rawUsageJson = "{}";
  private String serviceTier = null;

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
    if (root.has("usageMetadata")) {
      if (!root.get("usageMetadata").isObject()) {
        throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid usageMetadata");
      }
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
    long promptTokens = optionalTokenCount(usageNode, "promptTokenCount");
    long output = optionalTokenCount(usageNode, "candidatesTokenCount");
    long providerTotal = optionalTokenCount(usageNode, "totalTokenCount");
    long cached = optionalTokenCount(usageNode, "cachedContentTokenCount");
    long reasoning = optionalTokenCount(usageNode, "thoughtsTokenCount");
    long ordinaryInput = promptTokens >= cached ? promptTokens - cached : 0L;
    String tier = null;
    if (usageNode.has("serviceTier")) {
      JsonNode value = usageNode.get("serviceTier");
      if (!value.isTextual() || value.textValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: serviceTier");
      }
      tier = value.textValue();
    }

    this.latestUsage =
        new ModelUsage(ordinaryInput, output, cached, 0L, 0L, reasoning, providerTotal);

    this.serviceTier = tier;
    this.rawUsageJson = usageNode.toString();
  }

  private static long optionalTokenCount(JsonNode usageNode, String field) {
    JsonNode value = usageNode.get(field);
    if (value == null) {
      return 0L;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0L) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: " + field);
    }
    return value.longValue();
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

    for (int i = 0; i < partsArray.size(); i++) {
      processPart(i, partsArray.get(i));
    }
  }

  /**
   * 累积单个原生 part。
   *
   * <p>官方协议没有定义跨 chunk 的通用 Part 合并算法，因此这里只承认两类可证明安全的文本合并：同一 slot 上「形状为超集 + 文本严格扩展 +
   * 双方均无签名」的累计快照替换，以及双方都仅含 text/thought 的朴素增量累积。其余 part（functionCall、inlineData / toolResponse 等
   * union 成员，以及任何带未知字段的 part）一律作为完整原生边界原样保留，绝不递归、拼接或猜测未知字段，也绝不跨 part 迁移或改写 thoughtSignature。
   */
  private void processPart(int index, JsonNode partNode) {
    boolean functionCallPart = partNode.has("functionCall");
    String signature = readThoughtSignature(partNode);
    String text = readPartText(partNode);
    boolean thought = isThoughtPart(partNode);
    ObjectNode normalizedPart = normalizedRawPart(partNode);
    TrackedPart slot = index < trackedParts.size() ? trackedParts.get(index) : null;

    // 1) 与同一 slot 已记录的原生 part 完全相等：同一快照重复发送的可证明证据，无需重复保留也不重复计入文本。
    if (slot != null && slot.rawPart.equals(normalizedPart)) {
      return;
    }

    // 2) 同一 slot 的累计快照替换：上游把同一 part 的完整累积形态再发一次，整体替换为上游事实。
    if (text != null
        && !functionCallPart
        && signature == null
        && slot != null
        && slot.canAdoptCumulativeText(normalizedPart, text, thought)) {
      String delta = text.substring(slot.text.length());
      slot.replaceWith(normalizedPart, text);
      emitTextDelta(delta, thought);
      return;
    }

    // 3) 朴素 text/thought part 的增量累积：双方都只含 text/thought，累积不会丢失任何字段语义。
    if (text != null && signature == null && !functionCallPart && isPlainPartShape(partNode)) {
      TrackedPart lastPart = lastTrackedPart();
      if (lastPart != null
          && lastPart.plainShape
          && lastPart.hasText
          && lastPart.thought == thought) {
        String accumulated = lastPart.text.toString();
        String delta =
            accumulated.isEmpty() || !text.startsWith(accumulated)
                ? text
                : text.substring(accumulated.length());
        if (!delta.isEmpty()) {
          lastPart.text.append(delta);
          emitTextDelta(delta, thought);
        }
        return;
      }
    }

    // 4) 完整原生 part 边界：原样保留，未知字段与未知 union 成员绝不合并、绝不丢弃。
    TrackedPart trackedPart = new TrackedPart(normalizedPart, signature, thought);
    trackedParts.add(trackedPart);
    if (text != null) {
      trackedPart.text.append(text);
      emitTextDelta(text, thought);
    }
    if (functionCallPart) {
      captureFunctionCall(trackedPart, partNode.get("functionCall"));
      emitFunctionCall(trackedPart);
      if (text != null) {
        // text 与 functionCall 属于同一个 data oneof：replay 只能承载其一，显式不冻结而非静默丢弃。
        this.replayFreezable = false;
      }
    }
  }

  /** 捕捉 functionCall 的规范化状态：id 缺失时补协议 id；name/args 已在 {@link #validateParts} 严格校验。 */
  private void captureFunctionCall(TrackedPart trackedPart, JsonNode fnNode) {
    trackedPart.functionCallName = fnNode.get("name").asText();
    trackedPart.functionCallId =
        (fnNode.has("id") && !fnNode.get("id").isNull())
            ? fnNode.get("id").asText()
            : "call_" + nextToolOrdinal;
    trackedPart.functionCallArgs = fnNode.get("args");
  }

  /** 按 part 的 thought 语义发射规范化文本增量；空增量不发射。 */
  private void emitTextDelta(String delta, boolean thought) {
    if (delta.isEmpty()) {
      return;
    }
    bridge.emitEvent(
        thought
            ? new ProviderStreamEvent.ThinkingDelta(delta)
            : new ProviderStreamEvent.TextDelta(delta));
  }

  private TrackedPart lastTrackedPart() {
    return trackedParts.isEmpty() ? null : trackedParts.get(trackedParts.size() - 1);
  }

  /** 判断 part 的字段集合是否只含 text/thought，即可安全增量累积的朴素形状。 */
  private static boolean isPlainPartShape(JsonNode partNode) {
    Iterator<String> fieldNames = partNode.fieldNames();
    while (fieldNames.hasNext()) {
      if (!PLAIN_PART_FIELDS.contains(fieldNames.next())) {
        return false;
      }
    }
    return true;
  }

  /** 判断 candidate 是否声明了 base 的全部字段名：只有超集形状的替换才可能不丢字段。 */
  private static boolean carriesAllFieldNames(JsonNode candidate, JsonNode base) {
    Iterator<String> fieldNames = base.fieldNames();
    while (fieldNames.hasNext()) {
      if (!candidate.has(fieldNames.next())) {
        return false;
      }
    }
    return true;
  }

  /** 读取 part 的 thoughtSignature：null 与空白字符串都表示该 part 不携带签名。 */
  private static String readThoughtSignature(JsonNode partNode) {
    JsonNode signature = partNode.get("thoughtSignature");
    if (signature == null || signature.isNull() || signature.asText().isBlank()) {
      return null;
    }
    return signature.asText();
  }

  /** 读取 part 的 text：null 表示未声明。 */
  private static String readPartText(JsonNode partNode) {
    return hasPartText(partNode) ? partNode.get("text").asText() : null;
  }

  private static boolean hasPartText(JsonNode partNode) {
    return partNode.has("text") && !partNode.get("text").isNull();
  }

  private static boolean isThoughtPart(JsonNode partNode) {
    return partNode.path("thought").asBoolean(false);
  }

  /** 保真化原生 part：null text 与空白 thoughtSignature 不承载语义且无法写入合法 replay，按不存在处理。 */
  private static ObjectNode normalizedRawPart(JsonNode partNode) {
    ObjectNode rawPart = partNode.deepCopy();
    if (rawPart.has("text") && rawPart.get("text").isNull()) {
      rawPart.remove("text");
    }
    JsonNode signature = rawPart.get("thoughtSignature");
    if (signature != null && (signature.isNull() || signature.asText().isBlank())) {
      rawPart.remove("thoughtSignature");
    }
    return rawPart;
  }

  /** 严格校验 part 已知字段的类型：已知字段无法保真时明确 INVALID_RESPONSE；未知字段与未知 union 成员不在此列，一律 opaque 透传。 */
  private void validateParts(ArrayNode partsArray) {
    for (JsonNode partNode : partsArray) {
      if (partNode == null || !partNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "candidate part must be a JSON object");
      }
      validatePartKnownFields(partNode);
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

  /** text/thought/thoughtSignature 是文本累积与签名回放直接依赖的已知字段，类型不合法时绝不猜测或改写。 */
  private static void validatePartKnownFields(JsonNode partNode) {
    if (partNode.has("text")
        && !partNode.get("text").isNull()
        && !partNode.get("text").isTextual()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "part text must be a string");
    }
    if (partNode.has("thought")
        && !partNode.get("thought").isNull()
        && !partNode.get("thought").isBoolean()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "part thought must be a boolean");
    }
    if (partNode.has("thoughtSignature")
        && !partNode.get("thoughtSignature").isNull()
        && !partNode.get("thoughtSignature").isTextual()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "part thoughtSignature must be a string");
    }
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
      if (tp.thought) {
        fullThinking.append(tp.text);
      } else {
        fullText.append(tp.text);
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
            this.serviceTier,
            this.rawUsageJson);

    ProviderReplayState replayState = null;
    if (this.stopReason == GenerationStopReason.COMPLETE && this.replayFreezable) {
      replayState = buildReplayState();
    }

    return new ProviderCompletion(response, replayState);
  }

  private ProviderReplayState buildReplayState() {
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "model");
    ArrayNode partsArray = payload.putArray("parts");

    for (TrackedPart tp : trackedParts) {
      // 完整原生 part：未知字段与未知 union 成员原样保留
      ObjectNode partNode = tp.rawPart.deepCopy();
      if (tp.isFunctionCall()) {
        if (tp.functionCallName == null
            || tp.functionCallName.isBlank()
            || tp.functionCallId == null
            || tp.functionCallId.isBlank()
            || tp.functionCallArgs == null
            || !tp.functionCallArgs.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "invalid function call in replay construction");
        }
        // 已知 functionCall 以规范化累积结果写回（id 稳定、args 显式为 JSON 对象）
        ObjectNode fnNode = partNode.putObject("functionCall");
        fnNode.put("name", tp.functionCallName);
        fnNode.put("id", tp.functionCallId);
        fnNode.set("args", tp.functionCallArgs.deepCopy());
      } else if (tp.text.length() > 0) {
        // 已知 text/thought 以规范化累积结果写回；纯未知 Part 不写回空 text
        partNode.put("text", tp.text.toString());
        if (tp.thought) {
          partNode.put("thought", true);
        }
      }
      if (tp.signature != null) {
        partNode.put("thoughtSignature", tp.signature);
      }
      partsArray.add(partNode);
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

  /** 单个原生 part 的累积状态：rawPart 保真，已知字段由规范化累积结果统一写回。 */
  private static final class TrackedPart {

    /** 原生 part 的保真化副本（已剔除 null text 与空白签名），由累积器持有：未知字段与未知 union 成员原样保留，供下一轮 opaque replay。 */
    ObjectNode rawPart;

    /** part 级 thought=true。 */
    final boolean thought;

    /** 非空白 thoughtSignature；null 表示该 part 不携带签名。 */
    final String signature;

    /** 原生 part 是否声明了 text 字段。 */
    final boolean hasText;

    /** 字段集合是否只含 text/thought；被上游超集形状替换后重新计算。 */
    boolean plainShape;

    /** 规范化累积文本。 */
    final StringBuilder text = new StringBuilder();

    String functionCallName = null;
    String functionCallId = null;
    JsonNode functionCallArgs = null;
    boolean functionCallEmitted = false;

    TrackedPart(ObjectNode rawPart, String signature, boolean thought) {
      this.rawPart = rawPart;
      this.thought = thought;
      this.signature = signature;
      this.hasText = hasPartText(rawPart);
      this.plainShape = isPlainPartShape(rawPart);
    }

    boolean isFunctionCall() {
      return rawPart.has("functionCall");
    }

    /**
     * 判断上游再次给出的同一 slot part 是否可证明为该 part 的完整累积形态。
     *
     * <p>成立条件：本地 part 无签名且非 functionCall 边界、双方 thought 语义一致、双方都声明 text、上游字段名是本地字段名的超集（替换不会静默丢字段），
     * 且上游文本严格扩展本地累积文本。带签名的 part 绝不参与该替换，因此签名永远不会被跨 part 迁移或改写。
     */
    boolean canAdoptCumulativeText(
        ObjectNode incomingPart, String incomingText, boolean incomingThought) {
      if (signature != null || isFunctionCall() || !hasText || thought != incomingThought) {
        return false;
      }
      if (!carriesAllFieldNames(incomingPart, rawPart)) {
        return false;
      }
      return incomingText.startsWith(text.toString()) && incomingText.length() > text.length();
    }

    /** 以同一 slot 的上游完整累积形态整体替换本地 part：只做原样替换，绝不逐字段递归合并未知字段。 */
    void replaceWith(ObjectNode incomingPart, String accumulatedText) {
      this.rawPart = incomingPart;
      this.plainShape = isPlainPartShape(this.rawPart);
      this.text.setLength(0);
      this.text.append(accumulatedText);
    }
  }
}
