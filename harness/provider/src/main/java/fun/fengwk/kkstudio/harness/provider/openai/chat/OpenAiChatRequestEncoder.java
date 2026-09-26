package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.ProviderProtocolOptionsJson;
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
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 OpenAI 规范的 UTF-8 请求 JSON 字节数组， 并提取冻结的
 * sourcePrefixHash。请求体以 variant 的 native protocolOptions
 * 为合并基座：运行时所有权字段（model、messages、stream、输出预算、prompt cache、已声明的 reasoning）优先并在 native 声明时冲突，
 * 其余官方字段原样保留。
 *
 * <p>assistant replay 只在 payload 仅含可等价重建的事实（role、文本 content、恰好 {@code
 * id/type/function{name,arguments}} 的现代 function tool_calls）时才允许在 affinity/sourcePrefixHash
 * 失配后退回语义编码；{@code refusal} 区分、{@code reasoning_content}、{@code reasoning_details}、{@code
 * audio}、已废弃的 {@code function_call}、custom/未知调用与任何额外顶层/嵌套字段都是 durable 无法表达的 native-only 事实，失配时必须
 * fail closed 而不是静默丢弃。
 */
final class OpenAiChatRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ALLOWED_TOOL_CALL_FIELDS = Set.of("id", "type", "function");
  private static final Set<String> ALLOWED_TOOL_FUNCTION_FIELDS = Set.of("name", "arguments");

  /** 运行时所有权字段：native protocolOptions 一旦声明即冲突，避免厂商选项覆盖运行时事实。 */
  private static final Set<String> RUNTIME_OWNED_FIELDS =
      Set.of(
          "model",
          "messages",
          "stream",
          "max_tokens",
          "max_completion_tokens",
          "prompt_cache_key",
          "prompt_cache_retention",
          "prompt_cache_options");

  /** 回放时值为 null 即视为未声明的 known 字段；与语义 fallback 的省略规则保持一致。 */
  private static final List<String> NULLABLE_REPLAY_FIELDS =
      List.of("content", "refusal", "reasoning_content", "reasoning_details", "tool_calls");

  /** 可等价重建的 fallback-safe 顶层字段：其余字段（refusal、reasoning_*、audio、function_call 等）都是 native-only 事实。 */
  private static final Set<String> FALLBACK_SAFE_REPLAY_FIELDS =
      Set.of("role", "content", "tool_calls");

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

    // variant 的原生协议选项是请求体的合并基座：官方顶层能力无损保留，运行时所有权字段随后覆盖并在 native 声明时冲突
    ObjectNode root = ProviderProtocolOptionsJson.copyOfOptions(request.variant());
    rejectRuntimeOwnedNativeFields(root);

    root.put("model", request.model().modelId());
    root.put("stream", true);

    applyStreamOptions(root, config);

    applyReasoningParameters(root, request.model(), request.variant(), config);
    // 输出预算只来自 Model 级 limit.output（普通请求再按剩余上下文收敛），不来自 variant。
    root.put("max_tokens", request.outputTokens());

    // native tools 与运行时 function tools 合并为同一个最终数组，并共同参与 prefix hash
    ArrayNode toolsArray = mergeTools(root, request.tools());

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
        wireMessagesArray.add(encodeMessage(message));
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

  /** native protocolOptions 不得声明运行时所有权字段：冲突一律以 INVALID_REQUEST 明确失败，且错误信息只描述字段名，不回显厂商值。 */
  private static void rejectRuntimeOwnedNativeFields(ObjectNode root) {
    for (String field : RUNTIME_OWNED_FIELDS) {
      if (root.has(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions must not declare runtime-owned field " + field);
      }
    }
  }

  /**
   * {@code stream_options} 由运行时与 native 共同构造：native 必须是 object 且保留自己的子字段，{@code include_usage}
   * 由运行时独占。
   */
  private static void applyStreamOptions(ObjectNode root, OpenAiChatConfiguration config) {
    JsonNode nativeOptions = root.get("stream_options");
    ObjectNode streamOptions;
    if (nativeOptions == null) {
      streamOptions = root.putObject("stream_options");
    } else {
      if (!nativeOptions.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions field stream_options must be a JSON object");
      }
      if (nativeOptions.has("include_usage")) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions must not declare runtime-owned field stream_options.include_usage");
      }
      streamOptions = (ObjectNode) nativeOptions;
    }
    streamOptions.put("include_usage", config.includeUsage());
  }

  /**
   * native tools 必须是 array 且原样保留，运行时 function tools 追加在其后；返回的合并数组是 wire 事实，参与 prefix hash 与 cache
   * 打标。
   *
   * <p>native 未声明 tools 且运行时无工具时返回 {@code null}，不产生空的 {@code tools} 字段。
   */
  private static ArrayNode mergeTools(ObjectNode root, List<ProviderToolDefinition> runtimeTools) {
    JsonNode nativeTools = root.get("tools");
    ArrayNode toolsArray = null;
    if (nativeTools != null) {
      if (!nativeTools.isArray()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "protocolOptions field tools must be an array");
      }
      toolsArray = (ArrayNode) nativeTools;
    }
    ArrayNode encodedRuntimeTools = encodeTools(runtimeTools);
    if (encodedRuntimeTools != null) {
      if (toolsArray == null) {
        toolsArray = NODES.arrayNode();
      }
      toolsArray.addAll(encodedRuntimeTools);
    }
    if (toolsArray != null) {
      root.set("tools", toolsArray);
    }
    return toolsArray;
  }

  /**
   * reasoning effort 编码：未声明（null）表示不覆盖服务端默认，此时 native 的 {@code reasoning_effort} / {@code thinking}
   * 属于厂商原生事实，原样保留； 已声明（含 {@code off}）时这两个字段由运行时独占，native 声明即冲突，随后按 STANDARD / DEEPSEEK 写现有字段。非
   * reasoning 模型不发送推理字段。
   */
  private static void applyReasoningParameters(
      ObjectNode root,
      ModelDescriptor model,
      ModelVariant variant,
      OpenAiChatConfiguration config) {
    String effort = variant == null ? null : variant.reasoningEffort();
    if (effort == null) {
      return;
    }
    if (root.has("reasoning_effort") || root.has("thinking")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions must not declare runtime-owned field reasoning_effort/thinking");
    }
    if (!model.reasoning()) {
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

  private static ObjectNode encodeMessage(ProviderMessage message) {
    return switch (message.role()) {
      case USER -> encodeUserMessage(message);
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

  private static ObjectNode encodeUserMessage(ProviderMessage message) {
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
        ObjectNode imgPart = contents.addObject();
        imgPart.put("type", "image_url");
        ObjectNode imgUrl = imgPart.putObject("image_url");
        imgUrl.put("url", ib.source());
      } else if (block instanceof ProviderAudioBlock ab) {
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
      // 同 format 即使 affinity/hash 失配，也必须先严格校验 shape 与 durable 一致性，损坏必须 INVALID_REQUEST
      boolean nativeOnly = validateReplayPayload(replayState.payload(), message.contents());

      boolean runtimeMatch =
          currentPrefixHash != null
              && replayState.affinity().equals(descriptor.affinity(requestedModel))
              && replayState.sourcePrefixHash().equals(currentPrefixHash);
      if (runtimeMatch) {
        // 原位回放：known 字段已在 validateReplayPayload 中完成类型校验与 durable 一致性校验，其余厂商原生 assistant 字段原样透传
        return buildReplayMessage(replayState.payload());
      }
      if (nativeOnly) {
        // native-only 字段无法用 durable 语义表达：失配时只能 fail closed，绝不静默丢弃
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "native replay fields require matching affinity and source prefix hash");
      }
      // affinity 或 hash 失配但 payload 只含可等价重建的事实，走语义 fallback
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

  /**
   * 从 native replay payload 构造 wire assistant message。
   *
   * <p>provider 返回的其他合法 assistant 字段（如 {@code audio}、{@code function_call}
   * 及未识别的未来字段）不在协议白名单内，但属于厂商原生事实，必须原样透传； known 字段为 null 时与语义 fallback 一样省略。
   */
  private static ObjectNode buildReplayMessage(JsonNode payload) {
    ObjectNode msgNode = (ObjectNode) payload.deepCopy();
    msgNode.put("role", "assistant");
    for (String field : NULLABLE_REPLAY_FIELDS) {
      JsonNode value = msgNode.get(field);
      if (value != null && value.isNull()) {
        msgNode.remove(field);
      }
    }
    return msgNode;
  }

  /** 完成 replay payload 的结构、已知字段类型与 durable 一致性校验，并返回它是否携带 durable 无法等价重建的原生事实。 */
  private static boolean validateReplayPayload(
      JsonNode payload, List<ProviderContentBlock> durableContents) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: not a JSON object");
    }

    // 校验 role
    if (!payload.has("role")
        || !payload.get("role").isTextual()
        || !"assistant".equals(payload.get("role").textValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: illegal role");
    }

    // 顶层 fallback-safe 字段只有 role/content/tool_calls：refusal/reasoning_*/audio/function_call
    // 与任何未知字段都是
    // durable 无法重建的 native-only 事实（显式 null 不承载事实，按未声明处理）
    boolean nativeOnly = false;
    Iterator<String> topFields = payload.fieldNames();
    while (topFields.hasNext()) {
      String field = topFields.next();
      JsonNode value = payload.get(field);
      if (!FALLBACK_SAFE_REPLAY_FIELDS.contains(field) && value != null && !value.isNull()) {
        nativeOnly = true;
      }
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

    // 校验 refusal 类型
    if (payload.has("refusal")) {
      JsonNode refusalNode = payload.get("refusal");
      if (!refusalNode.isNull() && !refusalNode.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: illegal refusal type");
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
      if (!toolCallsNode.isNull()) {
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
          // 恰好 id/type/function{name,arguments} 才是可等价重建的现代 function 调用；额外嵌套字段是原生事实
          boolean reconstructibleCall = true;
          Iterator<String> callFields = callNode.fieldNames();
          while (callFields.hasNext()) {
            String field = callFields.next();
            if (!ALLOWED_TOOL_CALL_FIELDS.contains(field)) {
              reconstructibleCall = false;
            }
          }
          if (!callNode.has("id")
              || !callNode.get("id").isTextual()
              || callNode.get("id").textValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid OpenAI chat assistant replay payload: tool call id must be a non-blank string");
          }
          if (!callNode.has("type") || !callNode.get("type").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid OpenAI chat assistant replay payload: tool call type must be function");
          }
          if (!"function".equals(callNode.get("type").textValue())) {
            // custom/未知调用类型无法用 durable 工具调用重建：只允许原位回放
            nativeOnly = true;
            continue;
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
              reconstructibleCall = false;
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
          if (!reconstructibleCall) {
            nativeOnly = true;
          }
        }
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

    StringBuilder payloadText = new StringBuilder();
    if (payload.has("content") && payload.get("content").isTextual()) {
      payloadText.append(payload.get("content").textValue());
    }
    if (payload.has("refusal") && payload.get("refusal").isTextual()) {
      payloadText.append(payload.get("refusal").textValue());
    }
    if (!payloadText.toString().equals(durableText.toString())) {
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

    return nativeOnly;
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
