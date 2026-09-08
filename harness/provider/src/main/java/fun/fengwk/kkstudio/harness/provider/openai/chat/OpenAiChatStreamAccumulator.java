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

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final OpenAiChatConfiguration config;
  private final String frozenSourcePrefixHash;
  private final OpenAiChatStreamBridge bridge;

  private String messageId = null;
  private String serviceTier = null;
  private GenerationStopReason stopReason = null;
  private boolean seenDone = false;

  private final StringBuilder textBuilder = new StringBuilder();
  private final StringBuilder reasoningContentBuilder = new StringBuilder();
  private JsonNode reasoningDetailsNode = null;

  private final Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();

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

    // 处理 usage
    if (root.has("usage") && root.get("usage").isObject()) {
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

      // 1. content
      if (delta.has("content") && delta.get("content").isTextual()) {
        String contentDelta = delta.get("content").textValue();
        if (!contentDelta.isEmpty()) {
          textBuilder.append(contentDelta);
          bridge.emitEvent(new ProviderStreamEvent.TextDelta(contentDelta));
        }
      }

      // 2. reasoning_content
      if (delta.has("reasoning_content") && delta.get("reasoning_content").isTextual()) {
        String reasoningDelta = delta.get("reasoning_content").textValue();
        if (!reasoningDelta.isEmpty()) {
          reasoningContentBuilder.append(reasoningDelta);
          bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(reasoningDelta));
        }
      }

      // 3. reasoning_details: 仅保留至 native replay
      if (delta.has("reasoning_details") && !delta.get("reasoning_details").isNull()) {
        mergeReasoningDetails(delta.get("reasoning_details"));
      }

      // 4. tool_calls
      if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
        ArrayNode toolCallsArray = (ArrayNode) delta.get("tool_calls");
        for (JsonNode tcNode : toolCallsArray) {
          if (tcNode.isObject() && tcNode.has("index") && tcNode.get("index").isInt()) {
            int index = tcNode.get("index").asInt();
            ToolCallBuilder builder =
                toolCallBuilders.computeIfAbsent(index, i -> new ToolCallBuilder(i));

            String idDelta = null;
            if (tcNode.has("id") && tcNode.get("id").isTextual()) {
              idDelta = tcNode.get("id").textValue();
              builder.appendId(idDelta);
            }

            String nameDelta = null;
            String argsDelta = null;
            if (tcNode.has("function") && tcNode.get("function").isObject()) {
              JsonNode fn = tcNode.get("function");
              if (fn.has("name") && fn.get("name").isTextual()) {
                nameDelta = fn.get("name").textValue();
                builder.appendName(nameDelta);
              }
              if (fn.has("arguments") && fn.get("arguments").isTextual()) {
                argsDelta = fn.get("arguments").textValue();
                builder.appendArguments(argsDelta);
              }
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
    this.promptTokens = usageNode.path("prompt_tokens").asLong(0L);
    this.completionTokens = usageNode.path("completion_tokens").asLong(0L);
    this.totalTokens = usageNode.path("total_tokens").asLong(promptTokens + completionTokens);

    JsonNode promptDetails = usageNode.path("prompt_tokens_details");
    if (promptDetails.isObject()) {
      this.cachedTokens = promptDetails.path("cached_tokens").asLong(0L);
      if (promptDetails.has("cache_write_tokens")) {
        this.cacheWriteTokens = promptDetails.path("cache_write_tokens").asLong(0L);
      } else if (promptDetails.has("cache_creation_input_tokens")) {
        this.cacheWriteTokens = promptDetails.path("cache_creation_input_tokens").asLong(0L);
      }
    }

    JsonNode completionDetails = usageNode.path("completion_tokens_details");
    if (completionDetails.isObject()) {
      this.reasoningTokens = completionDetails.path("reasoning_tokens").asLong(0L);
    }

    try {
      this.rawUsageJson = OBJECT_MAPPER.writeValueAsString(usageNode);
    } catch (JsonProcessingException ignored) {
      this.rawUsageJson = "{}";
    }
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
            toolCalls.add(new ProviderToolCall(id, name, args));
          }
        } else {
          // COMPLETE
          if (!idValid || !nameValid || parsedJson == null) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "invalid tool call arguments JSON");
          }
          toolCalls.add(new ProviderToolCall(id, name, args));
        }
      }
    }

    // 互斥 Usage 计算
    long ordinaryInput = Math.max(0L, promptTokens - cachedTokens - cacheWriteTokens);
    long ordinaryOutput = Math.max(0L, completionTokens - reasoningTokens);
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
            textBuilder.toString(),
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
      ObjectNode payload = NODES.objectNode();
      payload.put("role", "assistant");
      if (!textBuilder.isEmpty() || toolCalls.isEmpty()) {
        payload.put("content", textBuilder.toString());
      }
      if (!toolCalls.isEmpty()) {
        ArrayNode tcArr = payload.putArray("tool_calls");
        for (ProviderToolCall tc : toolCalls) {
          ObjectNode tcObj = tcArr.addObject();
          tcObj.put("id", tc.id());
          tcObj.put("type", "function");
          ObjectNode fn = tcObj.putObject("function");
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
              descriptor.affinity(request.model().modelName()),
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
