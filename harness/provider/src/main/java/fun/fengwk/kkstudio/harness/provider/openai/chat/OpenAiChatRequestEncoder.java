package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.RequestBodySizeGuard;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Chat Completions 协议请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 OpenAI 规范的 UTF-8 请求 JSON 字节数组， 并提取冻结的 sourcePrefixHash。
 */
final class OpenAiChatRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ALLOWED_REPLAY_FIELDS =
      Set.of("role", "content", "tool_calls", "reasoning_content", "reasoning_details");
  private static final Set<String> ALLOWED_TOOL_CALL_FIELDS = Set.of("id", "type", "function");
  private static final Set<String> ALLOWED_TOOL_FUNCTION_FIELDS = Set.of("name", "arguments");

  /** 应用层最终 UTF-8 请求体字节上限守卫；默认使用共享的 192 MiB 应用上限。 */
  private final RequestBodySizeGuard bodySizeGuard;

  OpenAiChatRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  OpenAiChatRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  OpenAiChatEncodedRequest encode(
      ProviderRequest request, ProviderDescriptor descriptor, OpenAiChatConfiguration config) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(config, "config");

    ObjectNode root = NODES.objectNode();
    root.put("model", request.model().modelId());
    root.put("stream", true);

    ObjectNode streamOptions = root.putObject("stream_options");
    streamOptions.put("include_usage", config.includeUsage());

    applyReasoningParameters(root, request.model(), request.variant(), config);
    // 输出预算只来自 Model 级 limit.output（普通请求再按剩余上下文收敛），不来自 variant。
    root.put("max_tokens", request.outputTokens());

    ArrayNode toolsArray = encodeTools(request.tools());
    if (toolsArray != null && !toolsArray.isEmpty()) {
      root.set("tools", toolsArray);
    }

    // 从左到右构建 wire messages 并维护 prefix hash；系统指令合成为唯一的前导 system message。
    ArrayNode wireMessagesArray = NODES.arrayNode();
    wireMessagesArray.add(encodeSystemInstruction(request.systemInstruction()));
    for (ProviderMessage message : request.messages()) {
      if (message.role() == ProviderMessageRole.ASSISTANT) {
        String currentPrefixHash =
            OpenAiChatPrefixHasher.calculateHash(toolsArray, wireMessagesArray);
        wireMessagesArray.add(
            encodeAssistantMessage(
                message, descriptor, request.model().modelId(), currentPrefixHash));
      } else {
        wireMessagesArray.add(encodeMessage(message, config));
      }
    }

    // 冻结当前请求新 assistant 生成前的 sourcePrefixHash
    String sourcePrefixHash = OpenAiChatPrefixHasher.calculateHash(toolsArray, wireMessagesArray);

    // 应用 Prompt Cache 策略与断点打标
    applyPromptCache(root, wireMessagesArray, request.cacheControl(), config);

    root.set("messages", wireMessagesArray);

    byte[] bodyUtf8Bytes;
    try {
      bodyUtf8Bytes = OBJECT_MAPPER.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize OpenAI chat request to JSON");
    }

    bodySizeGuard.enforce(bodyUtf8Bytes);

    return new OpenAiChatEncodedRequest(bodyUtf8Bytes, sourcePrefixHash);
  }

  /**
   * reasoning effort 编码：未声明（null）表示不覆盖服务端默认，不发送任何推理字段；{@code off} 表示显式关闭推理，其他厂商定义值原样下发。 STANDARD
   * 格式下显式关闭映射为本协议关闭值 {@code none}；DEEPSEEK 格式改用显式 {@code thinking} 开关，且仅在启用推理时附带 {@code
   * reasoning_effort}。非 reasoning 模型不发送推理字段。
   */
  private static void applyReasoningParameters(
      ObjectNode root,
      ModelDescriptor model,
      ModelVariant variant,
      OpenAiChatConfiguration config) {
    if (!model.reasoning()) {
      return;
    }
    String effort = variant == null ? null : variant.reasoningEffort();
    if (effort == null) {
      return;
    }
    boolean off = variant.reasoningOff();
    if (config.thinkingFormat() == OpenAiChatConfiguration.ThinkingFormat.DEEPSEEK) {
      ObjectNode thinking = root.putObject("thinking");
      if (off) {
        thinking.put("type", "disabled");
      } else {
        thinking.put("type", "enabled");
        root.put("reasoning_effort", effort);
      }
      return;
    }
    root.put("reasoning_effort", off ? "none" : effort);
  }

  private static ArrayNode encodeTools(List<ProviderToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    ArrayNode toolsArray = NODES.arrayNode();
    for (ProviderToolDefinition tool : tools) {
      ObjectNode toolNode = toolsArray.addObject();
      toolNode.put("type", "function");
      ObjectNode functionNode = toolNode.putObject("function");
      functionNode.put("name", tool.name());
      functionNode.put("description", tool.description());
      try {
        JsonNode params = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
        if (!params.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "tool inputSchemaJson must be a JSON object: " + tool.name());
        }
        functionNode.set("parameters", params);
      } catch (JsonProcessingException exception) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid tool inputSchemaJson for tool: " + tool.name());
      }
    }
    return toolsArray;
  }

  private static ObjectNode encodeMessage(ProviderMessage message, OpenAiChatConfiguration config) {
    return switch (message.role()) {
      case USER -> encodeUserMessage(message, config);
      case TOOL -> encodeToolMessage(message);
      case ASSISTANT -> throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "ASSISTANT message should be encoded with prefix hash context");
    };
  }

  /** 本协议没有顶层 system 字段，因此把请求唯一的系统指令合成为一条前导 system message。 */
  private static ObjectNode encodeSystemInstruction(String systemInstruction) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "system");
    msgNode.put("content", systemInstruction);
    return msgNode;
  }

  private static ObjectNode encodeUserMessage(
      ProviderMessage message, OpenAiChatConfiguration config) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "user");

    // 检查是否包含媒体块或仅单一文本
    boolean hasNonText = false;
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderVideoBlock) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "OpenAI chat does not support VIDEO");
      }
      if (!(block instanceof ProviderTextBlock)) {
        hasNonText = true;
      }
    }

    if (!hasNonText && message.contents().size() == 1) {
      ProviderTextBlock textBlock = (ProviderTextBlock) message.contents().get(0);
      msgNode.put("content", textBlock.text());
      return msgNode;
    }

    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderTextBlock tb) {
        ObjectNode textPart = contents.addObject();
        textPart.put("type", "text");
        textPart.put("text", tb.text());
      } else if (block instanceof ProviderImageBlock ib) {
        if (!config.mediaTypes().contains(OpenAiChatConfiguration.MediaType.IMAGE)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "IMAGE media type is not enabled in configuration");
        }
        ObjectNode imgPart = contents.addObject();
        imgPart.put("type", "image_url");
        ObjectNode imgUrl = imgPart.putObject("image_url");
        imgUrl.put("url", ib.source());
      } else if (block instanceof ProviderAudioBlock ab) {
        if (!config.mediaTypes().contains(OpenAiChatConfiguration.MediaType.AUDIO)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "AUDIO media type is not enabled in configuration");
        }
        String source = ab.source().trim();
        if (source.startsWith("http://") || source.startsWith("https://")) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "OpenAI chat audio requires base64 data; downloading or fabricating from URL is not supported");
        }
        String data;
        String format;
        if (source.startsWith("data:")) {
          int commaIdx = source.indexOf(',');
          if (commaIdx == -1) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid data URI for audio");
          }
          String header = source.substring(0, commaIdx);
          data = source.substring(commaIdx + 1);
          format = extractAudioFormatFromMime(header);
        } else {
          data = source;
          format = extractAudioFormatFromMime(ab.mediaType());
        }
        ObjectNode audioPart = contents.addObject();
        audioPart.put("type", "input_audio");
        ObjectNode inputAudio = audioPart.putObject("input_audio");
        inputAudio.put("data", data);
        inputAudio.put("format", format);
      } else if (block instanceof ProviderDocumentBlock db) {
        if (!config.mediaTypes().contains(OpenAiChatConfiguration.MediaType.PDF)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "PDF media type is not enabled in configuration");
        }
        String mime = db.mediaType().toLowerCase(Locale.ROOT);
        if (!mime.contains("pdf")) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "unsupported document media type: " + db.mediaType());
        }
        String fileData = db.source();
        if (!fileData.startsWith("data:")
            && !fileData.startsWith("http://")
            && !fileData.startsWith("https://")) {
          fileData = "data:application/pdf;base64," + fileData;
        }
        ObjectNode docPart = contents.addObject();
        docPart.put("type", "file");
        ObjectNode fileObj = docPart.putObject("file");
        fileObj.put("file_data", fileData);
        fileObj.put("filename", "pdf_file");
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported block in user message: " + block.getClass().getSimpleName());
      }
    }
    return msgNode;
  }

  private static String extractAudioFormatFromMime(String mimeOrHeader) {
    String lower = mimeOrHeader.toLowerCase(Locale.ROOT);
    if (lower.contains("wav")) {
      return "wav";
    }
    if (lower.contains("mp3") || lower.contains("mpeg")) {
      return "mp3";
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported audio format: " + mimeOrHeader);
  }

  private static ObjectNode encodeAssistantMessage(
      ProviderMessage message,
      ProviderDescriptor descriptor,
      String requestedModel,
      String currentPrefixHash) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "assistant");

    ProviderReplayState replayState = message.replayState();
    if (replayState != null && replayState.format() == ProviderReplayFormat.OPENAI_CHAT) {
      // 同 format 即使 affinity/hash 失配，也必须先严格校验 shape、白名单和 durable 一致性，损坏必须 INVALID_REQUEST
      validateReplayPayload(replayState.payload(), message.contents());

      boolean runtimeMatch =
          currentPrefixHash != null
              && replayState.affinity().equals(descriptor.affinity(requestedModel))
              && replayState.sourcePrefixHash().equals(currentPrefixHash);
      if (runtimeMatch) {
        JsonNode payload = replayState.payload();
        if (payload.has("content") && !payload.get("content").isNull()) {
          msgNode.set("content", payload.get("content").deepCopy());
        }
        if (payload.has("tool_calls") && payload.get("tool_calls").isArray()) {
          msgNode.set("tool_calls", payload.get("tool_calls").deepCopy());
        }
        if (payload.has("reasoning_content") && !payload.get("reasoning_content").isNull()) {
          msgNode.set("reasoning_content", payload.get("reasoning_content").deepCopy());
        }
        if (payload.has("reasoning_details") && !payload.get("reasoning_details").isNull()) {
          msgNode.set("reasoning_details", payload.get("reasoning_details").deepCopy());
        }
        return msgNode;
      }
      // affinity 或 hash 失配但 payload 合法一致，走语义 fallback
    }
    // 非 OPENAI_CHAT format 或失配时走语义回退

    // 语义回退（semantic fallback）
    StringBuilder textBuilder = new StringBuilder();
    ArrayNode toolCallsArray = NODES.arrayNode();
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderTextBlock tb) {
        textBuilder.append(tb.text());
      } else if (block instanceof ProviderToolCallBlock cb) {
        ProviderToolCall call = cb.toolCall();
        parseJsonObject(call.argumentsJson(), "toolCall argumentsJson must be a JSON object");
        ObjectNode callNode = toolCallsArray.addObject();
        callNode.put("id", call.id());
        callNode.put("type", "function");
        ObjectNode fnNode = callNode.putObject("function");
        fnNode.put("name", call.name());
        fnNode.put("arguments", call.argumentsJson());
      } else if (block instanceof ProviderThinkingBlock) {
        // Fallback 时 OpenAI 官方标准不保留 thinking 文本块
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported block in ASSISTANT message: " + block.getClass().getSimpleName());
      }
    }
    if (!textBuilder.isEmpty() || toolCallsArray.isEmpty()) {
      msgNode.put("content", textBuilder.toString());
    }
    if (!toolCallsArray.isEmpty()) {
      msgNode.set("tool_calls", toolCallsArray);
    }
    return msgNode;
  }

  private static void validateReplayPayload(
      JsonNode payload, List<ProviderContentBlock> durableContents) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: not a JSON object");
    }

    // 校验白名单字段
    Iterator<String> fields = payload.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!ALLOWED_REPLAY_FIELDS.contains(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: unknown field " + field);
      }
    }

    // 校验 role
    if (!payload.has("role")
        || !payload.get("role").isTextual()
        || !"assistant".equals(payload.get("role").textValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: illegal role");
    }

    // 校验 content 类型
    if (payload.has("content")) {
      JsonNode contentNode = payload.get("content");
      if (!contentNode.isNull() && !contentNode.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: illegal content type");
      }
    }

    // 校验 reasoning_content 类型
    if (payload.has("reasoning_content")) {
      JsonNode reasoningNode = payload.get("reasoning_content");
      if (!reasoningNode.isNull() && !reasoningNode.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: illegal reasoning_content type");
      }
    }

    // 校验 reasoning_details 类型
    if (payload.has("reasoning_details")) {
      JsonNode detailsNode = payload.get("reasoning_details");
      if (!detailsNode.isNull() && !detailsNode.isObject() && !detailsNode.isArray()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: illegal reasoning_details type");
      }
    }

    // 校验 tool_calls 及其子结构
    List<ProviderToolCall> payloadCalls = new ArrayList<>();
    if (payload.has("tool_calls")) {
      JsonNode toolCallsNode = payload.get("tool_calls");
      if (!toolCallsNode.isArray()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: illegal tool_calls type");
      }
      for (JsonNode callNode : toolCallsNode) {
        if (!callNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid OpenAI chat assistant replay payload: tool call must be a JSON object");
        }
        Iterator<String> callFields = callNode.fieldNames();
        while (callFields.hasNext()) {
          String field = callFields.next();
          if (!ALLOWED_TOOL_CALL_FIELDS.contains(field)) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid OpenAI chat assistant replay payload: unknown field in tool call: "
                    + field);
          }
        }
        if (!callNode.has("id")
            || !callNode.get("id").isTextual()
            || callNode.get("id").textValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid OpenAI chat assistant replay payload: tool call id must be a non-blank string");
        }
        if (!callNode.has("type")
            || !callNode.get("type").isTextual()
            || !"function".equals(callNode.get("type").textValue())) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid OpenAI chat assistant replay payload: tool call type must be function");
        }
        if (!callNode.has("function") || !callNode.get("function").isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid OpenAI chat assistant replay payload: tool call function must be a JSON object");
        }
        JsonNode fnNode = callNode.get("function");
        Iterator<String> fnFields = fnNode.fieldNames();
        while (fnFields.hasNext()) {
          String field = fnFields.next();
          if (!ALLOWED_TOOL_FUNCTION_FIELDS.contains(field)) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid OpenAI chat assistant replay payload: unknown field in tool call function: "
                    + field);
          }
        }
        if (!fnNode.has("name")
            || !fnNode.get("name").isTextual()
            || fnNode.get("name").textValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid OpenAI chat assistant replay payload: tool call function name must be a non-blank string");
        }
        if (!fnNode.has("arguments")
            || !fnNode.get("arguments").isTextual()
            || fnNode.get("arguments").textValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid OpenAI chat assistant replay payload: tool call function arguments must be a non-blank string");
        }
        String argumentsStr = fnNode.get("arguments").textValue();
        parseJsonObject(
            argumentsStr,
            "invalid OpenAI chat assistant replay payload: tool call function arguments must be a JSON object");
        payloadCalls.add(
            new ProviderToolCall(
                callNode.get("id").textValue(), fnNode.get("name").textValue(), argumentsStr));
      }
    }

    // 校验 durable 一致性
    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock tb) {
        durableThinking.append(tb.thinking());
      } else if (block instanceof ProviderToolCallBlock cb) {
        ProviderToolCall call = cb.toolCall();
        parseJsonObject(
            call.argumentsJson(),
            "invalid OpenAI chat assistant durable tool call arguments: must be a JSON object");
        durableCalls.add(call);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay: unsupported durable block "
                + block.getClass().getSimpleName());
      }
    }

    String payloadText =
        (payload.has("content") && payload.get("content").isTextual())
            ? payload.get("content").textValue()
            : "";
    if (!payloadText.equals(durableText.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    }

    String payloadThinking =
        (payload.has("reasoning_content") && payload.get("reasoning_content").isTextual())
            ? payload.get("reasoning_content").textValue()
            : "";
    if (!payloadThinking.equals(durableThinking.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: reasoning_content mismatch with durable contents");
    }

    if (payloadCalls.size() != durableCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    }
    for (int i = 0; i < payloadCalls.size(); i++) {
      ProviderToolCall p = payloadCalls.get(i);
      ProviderToolCall d = durableCalls.get(i);
      if (!p.id().equals(d.id())
          || !p.name().equals(d.name())
          || !p.argumentsJson().equals(d.argumentsJson())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: mismatch with durable contents");
      }
    }
  }

  private static ObjectNode encodeToolMessage(ProviderMessage message) {
    if (message.contents().size() != 1
        || !(message.contents().get(0) instanceof ProviderToolResultBlock resultBlock)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "TOOL message must contain exactly one ProviderToolResultBlock");
    }
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "tool");
    msgNode.put("tool_call_id", resultBlock.toolCallId());

    StringBuilder sb = new StringBuilder();
    for (ProviderContentBlock block : resultBlock.contents()) {
      if (block instanceof ProviderTextBlock tb) {
        sb.append(tb.text());
      } else if (block instanceof ProviderJsonBlock jb) {
        sb.append(jb.json());
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "TOOL message contains unsupported block: " + block.getClass().getSimpleName());
      }
    }
    msgNode.put("content", sb.toString());
    return msgNode;
  }

  private static void applyPromptCache(
      ObjectNode root,
      ArrayNode wireMessagesArray,
      ProviderCacheControl cacheControl,
      OpenAiChatConfiguration config) {
    switch (config.promptCacheMode()) {
      case AUTOMATIC -> {
        // AUTOMATIC: 不发任何 cache hint
      }
      case LEGACY -> {
        if (cacheControl != null && cacheControl.retention() != PromptCacheRetention.NONE) {
          root.put("prompt_cache_key", cacheControl.affinityKey());
          if (cacheControl.retention() == PromptCacheRetention.SHORT) {
            root.put("prompt_cache_retention", "in_memory");
          } else if (cacheControl.retention() == PromptCacheRetention.LONG) {
            root.put("prompt_cache_retention", "24h");
          }
        }
      }
      case GPT_5_6_EXPLICIT -> {
        // 根节点始终发 prompt_cache_options mode=explicit ttl=30m
        ObjectNode cacheOptions = root.putObject("prompt_cache_options");
        cacheOptions.put("mode", "explicit");
        cacheOptions.put("ttl", "30m");

        if (cacheControl != null && cacheControl.retention() != PromptCacheRetention.NONE) {
          root.put("prompt_cache_key", cacheControl.affinityKey());

          // 1. SYSTEM breakpoint: 最后一个 system 消息的最后一个 content part
          if (cacheControl.breakpoints().contains(PromptCacheBreakpoint.SYSTEM)) {
            markSystemBreakpoint(wireMessagesArray);
          }

          // 2. CONVERSATION breakpoint: 最后一个 conversation 消息的最后一个可标记 content part
          if (cacheControl.breakpoints().contains(PromptCacheBreakpoint.CONVERSATION)) {
            markConversationBreakpoint(wireMessagesArray);
          }
        }
      }
    }
  }

  private static void markSystemBreakpoint(ArrayNode wireMessagesArray) {
    for (int i = wireMessagesArray.size() - 1; i >= 0; i--) {
      JsonNode msgNode = wireMessagesArray.get(i);
      if (msgNode.isObject() && "system".equals(msgNode.path("role").asText())) {
        attachBreakpointToMessage((ObjectNode) msgNode);
        return;
      }
    }
  }

  private static void markConversationBreakpoint(ArrayNode wireMessagesArray) {
    for (int i = wireMessagesArray.size() - 1; i >= 0; i--) {
      JsonNode msgNode = wireMessagesArray.get(i);
      if (msgNode.isObject() && !"system".equals(msgNode.path("role").asText())) {
        if (attachBreakpointToMessage((ObjectNode) msgNode)) {
          return;
        }
      }
    }
  }

  private static boolean attachBreakpointToMessage(ObjectNode msgNode) {
    if (!msgNode.has("content")) {
      return false;
    }
    JsonNode contentNode = msgNode.get("content");
    if (contentNode.isTextual()) {
      String text = contentNode.textValue();
      ArrayNode parts = NODES.arrayNode();
      ObjectNode part = parts.addObject();
      part.put("type", "text");
      part.put("text", text);
      part.put("prompt_cache_breakpoint", true);
      msgNode.set("content", parts);
      return true;
    } else if (contentNode.isArray()) {
      ArrayNode parts = (ArrayNode) contentNode;
      if (!parts.isEmpty()) {
        JsonNode lastPart = parts.get(parts.size() - 1);
        if (lastPart.isObject()) {
          ((ObjectNode) lastPart).put("prompt_cache_breakpoint", true);
          return true;
        }
      }
    }
    return false;
  }

  private static JsonNode parseJsonObject(String json, String errorMessage) {
    if (json == null || json.isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
    try (JsonParser parser = OBJECT_MAPPER.createParser(json)) {
      JsonNode node = OBJECT_MAPPER.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
      }
      return node;
    } catch (Exception e) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
  }
}
