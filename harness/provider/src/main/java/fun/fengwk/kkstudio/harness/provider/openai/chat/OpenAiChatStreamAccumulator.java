package fun.fengwk.kkstudio.harness.provider.openai.chat;

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
 * OpenAI Chat Completions SSE 流式事件状态机与响应累积器。
 *
 * <p>支持 content、tool_calls fragments、reasoning_content delta、reasoning_details、 尾部 usage、终态校验、截断诊断及
 * native replay 构造。
 */
final class OpenAiChatStreamAccumulator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> VALID_FINISH_REASONS =
      Set.of("stop", "tool_calls", "length", "content_filter");

  /** known assistant 字段：replay 时由规范化 durable 事实重新写入，不沿用 native 片段形状。 */
  private static final Set<String> KNOWN_ASSISTANT_FIELDS =
      Set.of("content", "refusal", "reasoning_content", "reasoning_details", "tool_calls");

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final OpenAiChatConfiguration config;
  private final String frozenSourcePrefixHash;
  private final OpenAiChatStreamBridge bridge;

  private String messageId = null;
  private String serviceTier = null;
  private GenerationStopReason stopReason = null;
  private boolean seenDone = false;

  private final StringBuilder contentBuilder = new StringBuilder();
  private final StringBuilder refusalBuilder = new StringBuilder();
  private final StringBuilder reasoningContentBuilder = new StringBuilder();
  private JsonNode reasoningDetailsNode = null;

  private final Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();

  /**
   * provider 原生 assistant message：只合并 {@code choices[0].delta}，绝不混入 chunk 级 transport
   * metadata（id、object、usage 等）。
   *
   * <p>它保留规范化通道之外的官方事实（function_call、未来字段），audio 在冻结时仅保留请求侧 id， 并在终态作为 replay payload 的基座。
   */
  private final ObjectNode nativeAssistantMessage = NODES.objectNode();

  private long promptTokens = 0L;
  private long cachedTokens = 0L;
  private long cacheWriteTokens = 0L;
  private long completionTokens = 0L;
  private long reasoningTokens = 0L;
  private long totalTokens = 0L;
  private String rawUsageJson = "{}";

  OpenAiChatStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      OpenAiChatConfiguration config,
      String frozenSourcePrefixHash,
      OpenAiChatStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.config = Objects.requireNonNull(config, "config");
    this.frozenSourcePrefixHash =
        Objects.requireNonNull(frozenSourcePrefixHash, "frozenSourcePrefixHash");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
  }

  void handleData(String data) {
    if (bridge.isCancelled()) {
      return;
    }
    if (data == null || data.isBlank()) {
      return;
    }
    String trimmed = data.trim();
    if ("[DONE]".equals(trimmed)) {
      seenDone = true;
      return;
    }

    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(trimmed);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid SSE JSON payload");
    }

    if (!root.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE data must be a JSON object");
    }

    if (root.has("error")) {
      throw OpenAiChatErrorMapper.mapSseErrorEnvelope(root);
    }

    if (root.has("id") && root.get("id").isTextual()) {
      this.messageId = root.get("id").asText();
    }
    if (root.has("service_tier") && root.get("service_tier").isTextual()) {
      this.serviceTier = root.get("service_tier").asText();
    }

    // 处理 usage；流式中间块允许显式 null，其他非 Object 形态严格拒绝。
    if (root.has("usage") && !root.get("usage").isNull()) {
      if (!root.get("usage").isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "usage must be a JSON object");
      }
      parseUsageSnapshot(root.get("usage"));
    }

    // 处理 choices
    if (root.has("choices") && root.get("choices").isArray()) {
      ArrayNode choices = (ArrayNode) root.get("choices");
      if (!choices.isEmpty()) {
        JsonNode firstChoice = choices.get(0);
        if (firstChoice.isObject()) {
          parseChoice(firstChoice);
        }
      }
    }
  }

  private void parseChoice(JsonNode choice) {
    if (choice.has("delta") && choice.get("delta").isObject()) {
      JsonNode delta = choice.get("delta");
      mergeNativeDelta(delta);

      // 1. content
      if (delta.has("content") && delta.get("content").isTextual()) {
        String contentDelta = delta.get("content").textValue();
        if (!contentDelta.isEmpty()) {
          contentBuilder.append(contentDelta);
          bridge.emitEvent(new ProviderStreamEvent.TextDelta(contentDelta));
        }
      }

      // 2. refusal：协议中的成功助手输出，归一化到可见文本通道
      if (delta.has("refusal") && delta.get("refusal").isTextual()) {
        String refusalDelta = delta.get("refusal").textValue();
        if (!refusalDelta.isEmpty()) {
          refusalBuilder.append(refusalDelta);
          bridge.emitEvent(new ProviderStreamEvent.TextDelta(refusalDelta));
        }
      }

      // 3. reasoning_content
      if (delta.has("reasoning_content") && delta.get("reasoning_content").isTextual()) {
        String reasoningDelta = delta.get("reasoning_content").textValue();
        if (!reasoningDelta.isEmpty()) {
          reasoningContentBuilder.append(reasoningDelta);
          bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(reasoningDelta));
        }
      }

      // 4. reasoning_details: 仅保留至 native replay
      if (delta.has("reasoning_details") && !delta.get("reasoning_details").isNull()) {
        mergeReasoningDetails(delta.get("reasoning_details"));
      }

      // 5. tool_calls
      if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
        ArrayNode toolCallsArray = (ArrayNode) delta.get("tool_calls");
        for (JsonNode tcNode : toolCallsArray) {
          if (tcNode.isObject() && tcNode.has("index") && tcNode.get("index").isInt()) {
            int index = tcNode.get("index").asInt();
            // Custom/unknown calls remain native-only, not executable function intent.
            if (!tcNode.path("function").isObject()
                || (tcNode.path("type").isTextual()
                    && !"function".equals(tcNode.path("type").textValue()))) {
              continue;
            }
            ToolCallBuilder builder =
                toolCallBuilders.computeIfAbsent(index, i -> new ToolCallBuilder(i));

            String idDelta = null;
            if (tcNode.has("id") && tcNode.get("id").isTextual()) {
              idDelta = tcNode.get("id").textValue();
              builder.appendId(idDelta);
            }

            String nameDelta = null;
            String argsDelta = null;
            JsonNode fn = tcNode.get("function");
            if (fn.has("name") && fn.get("name").isTextual()) {
              nameDelta = fn.get("name").textValue();
              builder.appendName(nameDelta);
            }
            if (fn.has("arguments") && fn.get("arguments").isTextual()) {
              argsDelta = fn.get("arguments").textValue();
              builder.appendArguments(argsDelta);
            }

            if (idDelta != null || nameDelta != null || argsDelta != null) {
              bridge.emitEvent(
                  new ProviderStreamEvent.ToolCallDelta(index, idDelta, nameDelta, argsDelta));
            }
          }
        }
      }
    }

    // 检查 finish_reason
    if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
      String reasonText = choice.get("finish_reason").asText();
      if (!reasonText.isBlank()) {
        if (!VALID_FINISH_REASONS.contains(reasonText)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "unsupported finish_reason: " + reasonText);
        }
        this.stopReason = mapFinishReason(reasonText);
      }
    }
  }

  /**
   * 把一条 {@code choices[0].delta} 合并进原生 assistant message。
   *
   * <p>合并规则只描述「同名字段如何叠加」：字符串增量追加，带 {@code index} 的数组按 index 合并条目（tool_calls 片段因此按 index 累加）， object
   * 递归合并，其余标量覆盖。因此未规范化的官方字段与未来字段都能无损保留到 replay，而 chunk 级 transport metadata 不在合并范围内。
   */
  private void mergeNativeDelta(JsonNode delta) {
    Iterator<Map.Entry<String, JsonNode>> fields = delta.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      mergeNativeField(nativeAssistantMessage, field.getKey(), field.getValue());
    }
  }

  private static void mergeNativeField(ObjectNode target, String field, JsonNode incoming) {
    JsonNode existing = target.get(field);
    if (incoming.isTextual() && existing != null && existing.isTextual()) {
      target.put(field, existing.textValue() + incoming.textValue());
      return;
    }
    if (incoming.isObject() && existing != null && existing.isObject()) {
      mergeNativeObject((ObjectNode) existing, (ObjectNode) incoming);
      return;
    }
    if (incoming.isArray()
        && existing != null
        && existing.isArray()
        && isIndexedArray((ArrayNode) incoming)) {
      mergeNativeIndexedArray((ArrayNode) existing, (ArrayNode) incoming);
      return;
    }
    target.set(field, incoming.deepCopy());
  }

  private static void mergeNativeObject(ObjectNode target, ObjectNode incoming) {
    Iterator<Map.Entry<String, JsonNode>> fields = incoming.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      mergeNativeField(target, field.getKey(), field.getValue());
    }
  }

  private static boolean isIndexedArray(ArrayNode array) {
    if (array.isEmpty()) {
      return false;
    }
    for (JsonNode item : array) {
      if (!item.isObject()) {
        return false;
      }
      JsonNode index = item.get("index");
      if (index == null || !index.isIntegralNumber()) {
        return false;
      }
    }
    return true;
  }

  /** 按条目自身的 {@code index} 定位既有槽位并递归合并，槽位不存在时追加，保持 provider 给出的片段顺序。 */
  private static void mergeNativeIndexedArray(ArrayNode target, ArrayNode incoming) {
    for (JsonNode item : incoming) {
      int index = item.get("index").asInt();
      ObjectNode slot = indexedSlot(target, index);
      mergeNativeObject(slot, (ObjectNode) item);
    }
  }

  private static ObjectNode indexedSlot(ArrayNode target, int index) {
    for (JsonNode item : target) {
      if (!item.isObject()) {
        continue;
      }
      JsonNode itemIndex = item.get("index");
      if (itemIndex != null && itemIndex.isIntegralNumber() && itemIndex.asInt() == index) {
        return (ObjectNode) item;
      }
    }
    ObjectNode slot = target.addObject();
    slot.put("index", index);
    return slot;
  }

  private void mergeReasoningDetails(JsonNode incoming) {
    if (this.reasoningDetailsNode == null) {
      this.reasoningDetailsNode = incoming.deepCopy();
    } else if (this.reasoningDetailsNode.isArray() && incoming.isArray()) {
      ArrayNode currentArr = (ArrayNode) this.reasoningDetailsNode;
      for (JsonNode item : incoming) {
        currentArr.add(item.deepCopy());
      }
    } else {
      this.reasoningDetailsNode = incoming.deepCopy();
    }
  }

  private void parseUsageSnapshot(JsonNode usageNode) {
    this.promptTokens = optionalNonNegativeLong(usageNode, "prompt_tokens", 0L);
    this.completionTokens = optionalNonNegativeLong(usageNode, "completion_tokens", 0L);
    this.totalTokens =
        usageNode.has("total_tokens")
            ? requiredNonNegativeLong(usageNode.get("total_tokens"), "total_tokens")
            : 0L;

    JsonNode promptDetails = optionalObject(usageNode, "prompt_tokens_details");
    if (promptDetails != null && promptDetails.has("cached_tokens")) {
      this.cachedTokens =
          requiredNonNegativeLong(
              promptDetails.get("cached_tokens"), "prompt_tokens_details.cached_tokens");
    } else if (usageNode.has("prompt_cache_hit_tokens")) {
      this.cachedTokens =
          requiredNonNegativeLong(
              usageNode.get("prompt_cache_hit_tokens"), "prompt_cache_hit_tokens");
    } else if (usageNode.has("cached_tokens")) {
      this.cachedTokens = requiredNonNegativeLong(usageNode.get("cached_tokens"), "cached_tokens");
    } else {
      this.cachedTokens = 0L;
    }

    if (promptDetails != null && promptDetails.has("cache_write_tokens")) {
      this.cacheWriteTokens =
          requiredNonNegativeLong(
              promptDetails.get("cache_write_tokens"), "prompt_tokens_details.cache_write_tokens");
    } else if (promptDetails != null && promptDetails.has("cache_creation_input_tokens")) {
      this.cacheWriteTokens =
          requiredNonNegativeLong(
              promptDetails.get("cache_creation_input_tokens"),
              "prompt_tokens_details.cache_creation_input_tokens");
    } else {
      this.cacheWriteTokens = 0L;
    }

    JsonNode completionDetails = optionalObject(usageNode, "completion_tokens_details");
    if (completionDetails != null && completionDetails.has("reasoning_tokens")) {
      this.reasoningTokens =
          requiredNonNegativeLong(
              completionDetails.get("reasoning_tokens"),
              "completion_tokens_details.reasoning_tokens");
    } else {
      this.reasoningTokens = 0L;
    }

    try {
      this.rawUsageJson = OBJECT_MAPPER.writeValueAsString(usageNode);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cannot preserve provider usage payload");
    }
  }

  private static JsonNode optionalObject(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: " + field);
    }
    return value;
  }

  private static long optionalNonNegativeLong(JsonNode parent, String field, long fallback) {
    JsonNode value = parent.get(field);
    return value == null ? fallback : requiredNonNegativeLong(value, field);
  }

  private static long requiredNonNegativeLong(JsonNode value, String field) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0L) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: " + field);
    }
    return value.longValue();
  }

  private static long addUsageValues(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values overflow");
    }
  }

  private static long uncachedTokens(long total, long firstExcluded, long secondExcluded) {
    long excluded = addUsageValues(firstExcluded, secondExcluded);
    return excluded >= total ? 0L : total - excluded;
  }

  private static GenerationStopReason mapFinishReason(String reason) {
    return switch (reason) {
      case "stop", "tool_calls" -> GenerationStopReason.COMPLETE;
      case "length" -> GenerationStopReason.LENGTH;
      case "content_filter" -> GenerationStopReason.FILTERED;
      default -> throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unsupported finish_reason: " + reason);
    };
  }

  ProviderCompletion finish() {
    if (config.requireDone()) {
      if (!seenDone) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "stream completed without [DONE]");
      }
      if (stopReason == null) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "stream completed without valid finish_reason");
      }
    } else {
      if (stopReason == null) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "stream ended with EOF before valid finish_reason");
      }
    }

    boolean canReplay = (stopReason == GenerationStopReason.COMPLETE);
    List<ProviderToolCall> toolCalls = new ArrayList<>();
    Map<Integer, ProviderToolCall> completedCalls = new TreeMap<>();
    List<ProviderToolCallDiagnostic> diagnostics = new ArrayList<>();

    if (stopReason == GenerationStopReason.FILTERED) {
      // FILTERED 时 toolCalls 必须为空，不生成 replayState
      canReplay = false;
    } else {
      for (ToolCallBuilder b : toolCallBuilders.values()) {
        String id = b.id();
        String name = b.name();
        String args = b.arguments();

        boolean idValid = (id != null && !id.isBlank());
        boolean nameValid = (name != null && !name.isBlank());
        JsonNode parsedJson = tryParseJsonObject(args);

        if (stopReason == GenerationStopReason.LENGTH) {
          if (!idValid || !nameValid || parsedJson == null) {
            diagnostics.add(
                new ProviderToolCallDiagnostic(
                    b.index(),
                    idValid ? id : null,
                    nameValid ? name : null,
                    args,
                    "tool call truncated due to max tokens"));
            canReplay = false;
          } else {
            ProviderToolCall call = new ProviderToolCall(id, name, args);
            toolCalls.add(call);
            completedCalls.put(b.index(), call);
          }
        } else {
          // COMPLETE
          if (!idValid || !nameValid || parsedJson == null) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "invalid tool call arguments JSON");
          }
          ProviderToolCall call = new ProviderToolCall(id, name, args);
          toolCalls.add(call);
          completedCalls.put(b.index(), call);
        }
      }
    }

    // 互斥 Usage 计算
    long ordinaryInput = uncachedTokens(promptTokens, cachedTokens, cacheWriteTokens);
    long ordinaryOutput =
        reasoningTokens >= completionTokens ? 0L : completionTokens - reasoningTokens;
    long inputTokens = ordinaryInput;
    long outputTokens = ordinaryOutput;
    long cacheReadTokens = cachedTokens;
    long cacheWriteTokensVal = cacheWriteTokens;
    long cacheWriteLongTokensVal = 0L;
    long reasoningTokensVal = reasoningTokens;
    long providerTotalTokens = totalTokens;

    ModelUsage usage =
        new ModelUsage(
            inputTokens,
            outputTokens,
            cacheReadTokens,
            cacheWriteTokensVal,
            cacheWriteLongTokensVal,
            reasoningTokensVal,
            providerTotalTokens);

    ModelCost cost = ModelCost.calculate(request.model().pricing(), usage);

    ProviderResponse response =
        new ProviderResponse(
            contentBuilder.toString() + refusalBuilder,
            reasoningContentBuilder.toString(),
            Collections.unmodifiableList(toolCalls),
            stopReason,
            usage,
            cost,
            messageId,
            serviceTier,
            rawUsageJson,
            Collections.unmodifiableList(diagnostics));

    ProviderReplayState replayState = null;
    if (canReplay) {
      // replay 以 provider 原生 assistant message 为基座保留官方非规范化字段，known 字段再以 durable 事实补齐
      ObjectNode payload = nativeAssistantMessage.deepCopy();
      payload.put("role", "assistant");
      payload.remove("annotations");
      JsonNode audio = payload.get("audio");
      if (audio != null) {
        if (audio.isNull()) {
          payload.remove("audio");
        } else if (!audio.isObject()
            || !audio.path("id").isTextual()
            || audio.path("id").textValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "invalid assistant audio id");
        } else {
          payload.putObject("audio").put("id", audio.path("id").textValue());
        }
      }
      JsonNode nativeCalls = payload.get("tool_calls");
      for (String knownField : KNOWN_ASSISTANT_FIELDS) {
        payload.remove(knownField);
      }
      if (!contentBuilder.isEmpty() || (refusalBuilder.isEmpty() && toolCalls.isEmpty())) {
        payload.put("content", contentBuilder.toString());
      }
      if (!refusalBuilder.isEmpty()) {
        payload.put("refusal", refusalBuilder.toString());
      }
      if (nativeCalls != null && nativeCalls.isArray()) {
        ArrayNode tcArr = payload.putArray("tool_calls");
        for (JsonNode nativeCall : nativeCalls) {
          if (nativeCall.isObject()) {
            ObjectNode call = (ObjectNode) nativeCall.deepCopy();
            JsonNode index = call.remove("index");
            ProviderToolCall normalized =
                index != null
                        && index.isInt()
                        && call.path("function").isObject()
                        && (!call.path("type").isTextual()
                            || "function".equals(call.path("type").textValue()))
                    ? completedCalls.remove(index.intValue())
                    : null;
            if (normalized != null) {
              call.put("id", normalized.id());
              call.put("type", "function");
              ObjectNode fn = (ObjectNode) call.get("function");
              fn.put("name", normalized.name());
              fn.put("arguments", normalized.argumentsJson());
            }
            tcArr.add(call);
          } else {
            tcArr.add(nativeCall.deepCopy());
          }
        }
      }
      if (!completedCalls.isEmpty()) {
        ArrayNode tcArr = (ArrayNode) payload.get("tool_calls");
        if (tcArr == null) {
          tcArr = payload.putArray("tool_calls");
        }
        for (ProviderToolCall tc : completedCalls.values()) {
          ObjectNode call = tcArr.addObject();
          call.put("id", tc.id());
          call.put("type", "function");
          ObjectNode fn = call.putObject("function");
          fn.put("name", tc.name());
          fn.put("arguments", tc.argumentsJson());
        }
      }
      if (!reasoningContentBuilder.isEmpty()) {
        payload.put("reasoning_content", reasoningContentBuilder.toString());
      }
      if (reasoningDetailsNode != null && !reasoningDetailsNode.isNull()) {
        payload.set("reasoning_details", reasoningDetailsNode.deepCopy());
      }

      replayState =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_CHAT,
              descriptor.affinity(request.model().modelId()),
              frozenSourcePrefixHash,
              payload);
    }

    return new ProviderCompletion(response, replayState);
  }

  private static JsonNode tryParseJsonObject(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(text);
      if (node.isObject()) {
        return node;
      }
    } catch (JsonProcessingException ignored) {
      // Not a valid JSON object
    }
    return null;
  }

  private static final class ToolCallBuilder {
    private final int index;
    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    ToolCallBuilder(int index) {
      this.index = index;
    }

    int index() {
      return index;
    }

    void appendId(String val) {
      if (val != null) {
        id.append(val);
      }
    }

    void appendName(String val) {
      if (val != null) {
        name.append(val);
      }
    }

    void appendArguments(String val) {
      if (val != null) {
        arguments.append(val);
      }
    }

    String id() {
      return id.toString();
    }

    String name() {
      return name.toString();
    }

    String arguments() {
      return arguments.toString();
    }
  }
}
