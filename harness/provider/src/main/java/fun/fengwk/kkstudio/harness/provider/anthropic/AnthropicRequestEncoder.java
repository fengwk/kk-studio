package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.ProviderProtocolOptionsJson;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Anthropic Messages 协议的请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 Anthropic 规范的 UTF-8 请求 JSON 字节数组。
 *
 * <p>请求体以 variant 的 native {@link ProviderProtocolOptionsJson protocolOptions} 为合并起点：运行时所有权字段
 * （{@code model}/{@code max_tokens}/{@code stream}/{@code messages}/{@code system}/{@code
 * cache_control}） 冲突显式失败，{@code tools} 保留 native 条目后追加 runtime 工具，其余官方顶层字段无损保留；cache marker
 * 与请求体大小均基于最终合并结果。
 *
 * <p>{@code anthropic-beta} 能力是「配置列表 + 运行时强制能力」的有序并集：配置的 {@code anthropicBetaFeatures} 原样在前，BUDGET
 * 思考实际启用时追加 {@code interleaved-thinking-2025-05-14}，并集为空则不发送该头。
 *
 * <p>{@code cacheControl} 只表达留存档位：{@code retention != NONE} 时在 tools/system/历史边界写入固定的原生 {@code
 * cache_control} ephemeral 标记，{@code LONG} 额外携带 {@code ttl:"1h"}；会话 {@code key} 不属于 Anthropic
 * 原生选项，因此不下发。
 *
 * <p>assistant replay 与 system/时间前缀无关，只与 replay state 的 affinity（Provider/连接世代/wire 模型）绑定。affinity
 * 不匹配时按 durable 语义回退投影，绝不因为存在 replay 而拒绝；affinity 匹配时先校验真实 payload 形状与 durable 一致性，再原位回放全部原生事实（含
 * {@code thinking} 的 signature、{@code redacted_thinking}、未知 block 与官方附加字段）。 payload 以 {@code
 * compaction} block 开头时代表历史已被摘要，校验通过后清空已编码消息使该消息成为首条消息。
 */
final class AnthropicRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ALLOWED_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of("application/pdf");

  /**
   * 运行时独占的顶层请求事实：{@code cache_control} 表示 prompt cache 的运行时所有权（标记由本编码器写入
   * system/tools/messages），native options 一律不得声明，冲突显式失败。
   */
  private static final List<String> RUNTIME_OWNED_OPTION_FIELDS =
      List.of("model", "max_tokens", "stream", "messages", "system", "cache_control");

  private static final int MAX_CACHE_BREAKPOINTS = 4;
  private static final int MAX_REQUEST_BODY_BYTES = 32 * 1024 * 1024;

  private final AnthropicConfiguration configuration;

  AnthropicRequestEncoder() {
    this(AnthropicConfiguration.defaults());
  }

  AnthropicRequestEncoder(AnthropicConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  AnthropicConfiguration configuration() {
    return configuration;
  }

  AnthropicEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    return encode(request, descriptor, this.configuration);
  }

  AnthropicEncodedRequest encode(
      ProviderRequest request, ProviderDescriptor descriptor, AnthropicConfiguration config) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(config, "config");

    // native protocolOptions 是合并起点：运行时所有权字段由本编码器写入，其余官方顶层字段原样保留
    ObjectNode root = ProviderProtocolOptionsJson.copyOfOptions(request.variant());
    rejectRuntimeOwnedOptions(root);

    root.put("model", request.model().modelId());

    int maxTokens = request.outputTokens();
    root.put("max_tokens", maxTokens);
    root.put("stream", true);

    boolean requiresInterleavedThinkingBeta =
        applyReasoningParameters(root, request, config, maxTokens);

    // beta 能力并集依赖推理参数结论：配置顺序在前，运行时强制的 interleaved thinking 追加在后
    List<String> betaFeatures = resolveBetaFeatures(config, requiresInterleavedThinkingBeta);

    ArrayNode toolsArray = mergeTools(root, request.tools());

    // 系统指令是请求唯一的顶层 system；编码为单块数组以便在 SYSTEM 边界打缓存标记。
    ArrayNode unmarkedSystemArray = NODES.arrayNode();
    ObjectNode systemBlock = NODES.objectNode();
    systemBlock.put("type", "text");
    systemBlock.put("text", request.systemInstruction());
    unmarkedSystemArray.add(systemBlock);

    // 从左到右构建 wire messages，并记录可用于缓存标记的消息端点
    ArrayNode wireMessagesArray = NODES.arrayNode();
    List<ObjectNode> cacheEndpoints = new ArrayList<>();
    ObjectNode lastEligible = null;
    for (ProviderMessage msg : request.messages()) {
      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        // assistant 之前的完整前缀就是上次模型请求的端点，不受本轮新增块数量影响。
        addCacheEndpoint(cacheEndpoints, lastEligible);
        EncodedAssistantMessage assistant =
            encodeAssistantMessage(msg, descriptor, request.model().modelId());
        // compaction 回放代表已经摘要完成的历史：durable 校验完成后清空已编码消息，
        // 使 compaction assistant message 成为首条消息、被摘要历史整体省略。
        if (assistant.resetsHistory()) {
          wireMessagesArray.removeAll();
          cacheEndpoints.clear();
          lastEligible = null;
        }
        wireMessagesArray.add(assistant.node());
      } else if (msg.role() == ProviderMessageRole.USER) {
        wireMessagesArray.add(encodeUserMessage(msg));
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        wireMessagesArray.add(encodeToolMessage(msg));
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unexpected message role: " + msg.role());
      }
      ObjectNode messageEndpoint =
          latestEligibleBlock(wireMessagesArray.get(wireMessagesArray.size() - 1).path("content"));
      if (messageEndpoint != null) {
        lastEligible = messageEndpoint;
      }
    }
    addCacheEndpoint(cacheEndpoints, lastEligible);

    // 注入 cache marker
    applyCacheMarkers(request.cacheControl(), toolsArray, unmarkedSystemArray, cacheEndpoints);

    if (!toolsArray.isEmpty()) {
      root.set("tools", toolsArray);
    }
    root.set("system", unmarkedSystemArray);
    root.set("messages", wireMessagesArray);

    byte[] utf8Bytes;
    try {
      utf8Bytes = OBJECT_MAPPER.writeValueAsBytes(root);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize Anthropic request body");
    }

    if (utf8Bytes.length > MAX_REQUEST_BODY_BYTES) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "request body exceeds " + MAX_REQUEST_BODY_BYTES + " bytes limit");
    }

    return new AnthropicEncodedRequest(utf8Bytes, betaFeatures);
  }

  /** 求出本条请求实际发送的有序 beta 能力标识：配置列表原样在前，运行时强制的 interleaved thinking 仅在 BUDGET 思考实际启用时追加在后，已配置则不重复。 */
  private static List<String> resolveBetaFeatures(
      AnthropicConfiguration config, boolean requiresInterleavedThinkingBeta) {
    List<String> configured = config.anthropicBetaFeatures();
    if (!requiresInterleavedThinkingBeta
        || configured.contains(AnthropicConfiguration.INTERLEAVED_THINKING_BETA)) {
      return configured;
    }
    List<String> union = new ArrayList<>(configured);
    union.add(AnthropicConfiguration.INTERLEAVED_THINKING_BETA);
    return List.copyOf(union);
  }

  /**
   * reasoning effort 编码：{@code off} 显式关闭推理，发送 {@code thinking:{type:"disabled"}}；ADAPTIVE
   * 模式原样下发厂商定义值， BUDGET 模式仅将 {@code high/medium/low} 映射为 token 预算；null 不声明推理字段，由服务端默认决定，此时 native
   * {@code thinking}/{@code output_config} 原样保留。
   *
   * <p>一旦 runtime 要写入 {@code thinking}，native {@code thinking} 必须显式拒绝；{@code output_config} 只对
   * runtime 同样要写的 {@code effort} 子字段拒绝，其余官方子字段无损保留。
   */
  private static boolean applyReasoningParameters(
      ObjectNode root, ProviderRequest request, AnthropicConfiguration config, int maxTokens) {
    if (!request.model().reasoning()
        || request.variant() == null
        || request.variant().reasoningEffort() == null) {
      return false;
    }

    if (root.has("thinking")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions must not override runtime field: thinking");
    }

    if (request.variant().reasoningOff()) {
      ObjectNode thinking = root.putObject("thinking");
      thinking.put("type", "disabled");
      return false;
    }

    String effort = request.variant().reasoningEffort();

    AnthropicThinkingMode mode = config.anthropicThinkingMode();
    switch (mode) {
      case ADAPTIVE -> {
        ObjectNode thinking = root.putObject("thinking");
        thinking.put("type", "adaptive");
        thinking.put("display", "summarized");

        ObjectNode outputConfig = mergeOutputConfig(root);
        outputConfig.put("effort", effort);
        return false;
      }
      case BUDGET -> {
        int budgetTokens = mapBudgetTokens(effort);
        if (budgetTokens >= maxTokens) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "budget_tokens must be lower than max_tokens");
        }
        ObjectNode thinking = root.putObject("thinking");
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", budgetTokens);
        thinking.put("display", "summarized");
        return true;
      }
    }
    return false;
  }

  /** 以 native {@code output_config} 为起点合并 runtime effort，保留 format 等其余官方子字段。 */
  private static ObjectNode mergeOutputConfig(ObjectNode root) {
    JsonNode nativeOutputConfig = root.get("output_config");
    if (nativeOutputConfig == null) {
      return root.putObject("output_config");
    }
    if (!nativeOutputConfig.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions output_config must be a JSON object");
    }
    if (nativeOutputConfig.has("effort")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions must not override runtime field: output_config.effort");
    }
    return (ObjectNode) nativeOutputConfig;
  }

  private static int mapBudgetTokens(String effort) {
    if (effort == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported reasoning effort for budget thinking");
    }
    return switch (effort) {
      case "low" -> 2048;
      case "medium" -> 8192;
      case "high" -> 16384;
      default -> throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported reasoning effort for budget thinking");
    };
  }

  /** 拒绝 native options 覆盖运行时所有权字段：错误只报字段名，绝不回显 value。 */
  private static void rejectRuntimeOwnedOptions(ObjectNode options) {
    for (String field : RUNTIME_OWNED_OPTION_FIELDS) {
      if (options.has(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions must not override runtime field: " + field);
      }
    }
  }

  /**
   * 合并 native tools 与 runtime function tools：先保留 native 条目（server tools 等官方非 function 工具），再追加
   * runtime 工具；合并结果同时是 prefix hash、cache marker 与请求体大小的依据。
   */
  private static ArrayNode mergeTools(ObjectNode root, List<ProviderToolDefinition> runtimeTools) {
    JsonNode nativeTools = root.get("tools");
    if (nativeTools != null && !nativeTools.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions tools must be a JSON array");
    }

    ArrayNode merged = NODES.arrayNode();
    if (nativeTools != null) {
      Set<String> names = new HashSet<>();
      for (int i = 0; i < nativeTools.size(); i++) {
        JsonNode nativeTool = nativeTools.get(i);
        if (nativeTool.has("cache_control")) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions must not override runtime field: tools[" + i + "].cache_control");
        }
        JsonNode type = nativeTool.path("type");
        JsonNode name = nativeTool.path("name");
        if (!nativeTool.isObject()
            || !type.isTextual()
            || !type.textValue().matches("(web_search|web_fetch|code_execution)_[0-9]{8}")
            || !name.isTextual()
            || name.textValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools["
                  + i
                  + "] must be a supported hosted tool; bind client tools through runtime ProviderToolDefinition");
        }
        if (!names.add(name.textValue())) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools[" + i + "] duplicates a tool name");
        }
        for (ProviderToolDefinition runtimeTool : runtimeTools) {
          if (name.textValue().equals(runtimeTool.name())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "protocolOptions tools[" + i + "] name conflicts with runtime tool");
          }
        }
        merged.add(nativeTool.deepCopy());
      }
    }
    for (ProviderToolDefinition tool : runtimeTools) {
      merged.add(encodeTool(tool));
    }
    return merged;
  }

  private static ObjectNode encodeTool(ProviderToolDefinition tool) {
    ObjectNode toolNode = NODES.objectNode();
    toolNode.put("name", tool.name());
    if (tool.description() != null && !tool.description().isBlank()) {
      toolNode.put("description", tool.description());
    }
    JsonNode schemaNode;
    try {
      schemaNode = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool inputSchemaJson is not valid JSON");
    }
    if (!schemaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool input_schema must be a JSON object");
    }
    toolNode.set("input_schema", schemaNode);
    return toolNode;
  }

  private static ObjectNode encodeUserMessage(ProviderMessage message) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "user");
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      contents.add(encodeUserBlock(block));
    }
    return msgNode;
  }

  private static ObjectNode encodeUserBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", jsonBlock.json());
      return node;
    }
    if (block instanceof ProviderImageBlock imageBlock) {
      return encodeImageBlock(imageBlock);
    }
    if (block instanceof ProviderDocumentBlock documentBlock) {
      return encodeDocumentBlock(documentBlock);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported user content block type");
  }

  private static ObjectNode encodeImageBlock(ProviderImageBlock block) {
    if (!ALLOWED_IMAGE_TYPES.contains(block.mediaType())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported image media type");
    }
    ObjectNode node = NODES.objectNode();
    node.put("type", "image");
    ObjectNode sourceNode = node.putObject("source");
    String base64Data = extractBase64Data(block.source(), block.mediaType());
    if (base64Data != null) {
      sourceNode.put("type", "base64");
      sourceNode.put("media_type", block.mediaType());
      sourceNode.put("data", base64Data);
    } else if (isValidHttpUrl(block.source())) {
      sourceNode.put("type", "url");
      sourceNode.put("url", block.source());
    } else {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "image source must be a valid http(s) URL or base64 data URI matching mediaType");
    }
    return node;
  }

  private static ObjectNode encodeDocumentBlock(ProviderDocumentBlock block) {
    if (!ALLOWED_DOCUMENT_TYPES.contains(block.mediaType())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported document media type");
    }
    String base64Data = extractBase64Data(block.source(), block.mediaType());
    if (base64Data == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "document source must be a valid base64 data URI matching mediaType");
    }
    ObjectNode node = NODES.objectNode();
    node.put("type", "document");
    ObjectNode sourceNode = node.putObject("source");
    sourceNode.put("type", "base64");
    sourceNode.put("media_type", block.mediaType());
    sourceNode.put("data", base64Data);
    return node;
  }

  private static ObjectNode encodeToolMessage(ProviderMessage message) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "user");
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderToolResultBlock resultBlock) {
        ObjectNode resNode = contents.addObject();
        resNode.put("type", "tool_result");
        resNode.put("tool_use_id", resultBlock.toolCallId());
        if (resultBlock.error()) {
          resNode.put("is_error", true);
        }
        ArrayNode nestedContents = resNode.putArray("content");
        for (ProviderContentBlock nested : resultBlock.contents()) {
          nestedContents.add(encodeToolResultNestedBlock(nested));
        }
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "TOOL message contains non-tool-result block");
      }
    }
    return msgNode;
  }

  private static ObjectNode encodeToolResultNestedBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", jsonBlock.json());
      return node;
    }
    if (block instanceof ProviderImageBlock imageBlock) {
      return encodeImageBlock(imageBlock);
    }
    if (block instanceof ProviderDocumentBlock documentBlock) {
      return encodeDocumentBlock(documentBlock);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported content block inside tool_result");
  }

  private static EncodedAssistantMessage encodeAssistantMessage(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "assistant");

    ReplayDecision replay =
        evaluateReplay(message.replayState(), descriptor, requestedModel, message.contents());
    if (replay.replayable()) {
      ArrayNode replayedBlocks = NODES.arrayNode();
      for (JsonNode blockNode : replay.content()) {
        replayedBlocks.add(blockNode.deepCopy());
      }
      msgNode.set("content", replayedBlocks);
      return new EncodedAssistantMessage(msgNode, replay.startsHistory());
    }

    // Semantic fallback
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      contents.add(encodeAssistantFallbackBlock(block));
    }
    return new EncodedAssistantMessage(msgNode, false);
  }

  private static ReplayDecision evaluateReplay(
      ProviderReplayState replayState,
      ProviderDescriptor descriptor,
      String requestedModel,
      List<ProviderContentBlock> durableContents) {
    if (replayState == null || replayState.format() != ProviderReplayFormat.ANTHROPIC_MESSAGES) {
      return ReplayDecision.FALLBACK;
    }

    // 1. 源兼容性只由 affinity（Provider/连接世代/wire 模型）决定，与 system/时间前缀无关；不兼容时按 durable
    // 语义投影，绝不因为存在 replay 而拒绝请求。
    if (!replayState.affinity().equals(descriptor.affinity(requestedModel))) {
      return ReplayDecision.FALLBACK;
    }

    // 2. 兼容的 native replay 必须通过真实 payload 形状与 durable 一致性校验，再原位回放全部原生事实
    // （thinking signature、redacted thinking、未知 block 与官方附加字段均 opaque 保留）。
    JsonNode payload = replayState.payload();
    if (payload == null
        || !payload.isObject()
        || payload.size() != 2
        || !payload.has("role")
        || !payload.get("role").isTextual()
        || !"assistant".equals(payload.get("role").textValue())
        || !payload.has("content")
        || !payload.get("content").isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload");
    }
    ArrayNode contentArray = (ArrayNode) payload.get("content");
    boolean startsHistory = validatePayloadAgainstDurable(contentArray, durableContents);
    return new ReplayDecision(true, contentArray, startsHistory);
  }

  /**
   * 校验 native replay payload 的真实 block 形状与 durable 语义一致性，返回 payload 是否代表已摘要历史。
   *
   * <p>未知但合法的 provider block 与官方附加字段 opaque 透传；已知 block 只校验 durable 语义所必需的字段。
   */
  private static boolean validatePayloadAgainstDurable(
      ArrayNode contentArray, List<ProviderContentBlock> durableContents) {
    StringBuilder payloadText = new StringBuilder();
    StringBuilder payloadThinking = new StringBuilder();
    List<ProviderToolCall> payloadCalls = new ArrayList<>();

    int compactionIndex = -1;
    int blockIndex = 0;

    for (JsonNode item : contentArray) {
      if (!item.isObject() || !item.has("type") || !item.get("type").isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload block");
      }
      String type = item.get("type").textValue();
      if (type.isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload block");
      }
      switch (type) {
        case "text" -> {
          if (!item.has("text") || !item.get("text").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
          }
          validateCitations(item.get("citations"));
          payloadText.append(item.get("text").textValue());
        }
        case "thinking" -> {
          if (!item.has("thinking")
              || !item.get("thinking").isTextual()
              || !item.has("signature")
              || !item.get("signature").isTextual()
              || item.get("signature").textValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid thinking block in replay payload");
          }
          payloadThinking.append(item.get("thinking").textValue());
        }
        case "redacted_thinking" -> {
          if (!item.has("data")
              || !item.get("data").isTextual()
              || item.get("data").textValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid redacted_thinking block in replay payload");
          }
        }
        case "tool_use" -> {
          if (!item.has("id")
              || !item.get("id").isTextual()
              || item.get("id").textValue().isBlank()
              || !item.has("name")
              || !item.get("name").isTextual()
              || item.get("name").textValue().isBlank()
              || !item.has("input")
              || !item.get("input").isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid tool_use block in replay payload");
          }
          payloadCalls.add(
              new ProviderToolCall(
                  item.get("id").textValue(),
                  item.get("name").textValue(),
                  item.get("input").toString()));
        }
        case "compaction" -> {
          if (compactionIndex >= 0) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "duplicate compaction block in replay payload");
          }
          // 摘要块必须整体位于消息首部，代表此前历史已被压缩
          compactionIndex = blockIndex;
        }
        default -> {
          // 未知但合法的 provider block：opaque 透传，不参与 durable 一致性比对，也绝不伪装成已知 type
        }
      }
      blockIndex++;
    }

    if (compactionIndex > 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "compaction block must be the first content block of the replay message");
    }

    // 与 durable contents 比对
    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock pb) {
        durableThinking.append(pb.thinking());
      } else if (block instanceof ProviderToolCallBlock cb) {
        ProviderToolCall call = cb.toolCall();
        parseJsonObject(call.argumentsJson(), "durable tool call arguments must be a JSON object");
        durableCalls.add(call);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unsupported durable block in assistant message");
      }
    }

    if (!payloadText.toString().equals(durableText.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "replay payload text does not match durable content");
    }
    if (!payloadThinking.toString().equals(durableThinking.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload thinking does not match durable content");
    }
    if (payloadCalls.size() != durableCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload tool call count does not match durable content");
    }
    for (int i = 0; i < payloadCalls.size(); i++) {
      ProviderToolCall pCall = payloadCalls.get(i);
      ProviderToolCall dCall = durableCalls.get(i);
      if (!pCall.id().equals(dCall.id()) || !pCall.name().equals(dCall.name())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay payload tool call id/name does not match durable content");
      }
      JsonNode pInput =
          parseJsonObject(pCall.argumentsJson(), "replay tool call input must be a JSON object");
      JsonNode dInput =
          parseJsonObject(
              dCall.argumentsJson(), "durable tool call arguments must be a JSON object");
      if (!pInput.equals(dInput)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay payload tool call arguments do not match durable content");
      }
    }

    return compactionIndex == 0;
  }

  /** citations 是官方可扩展结构：只校验容器与元素形状，不做字段白名单。 */
  private static void validateCitations(JsonNode citationsNode) {
    if (citationsNode == null || citationsNode.isNull()) {
      return;
    }
    if (!citationsNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
    }
    for (JsonNode citation : citationsNode) {
      if (!citation.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
      }
    }
  }

  private static ObjectNode encodeAssistantFallbackBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      // thinking 降级为普通 text
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", thinkingBlock.thinking());
      return node;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      // jsonBlock 降级为普通 text 回放（如 tool_call_diagnostic）
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", jsonBlock.json());
      return node;
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      ProviderToolCall call = toolCallBlock.toolCall();
      ObjectNode node = NODES.objectNode();
      node.put("type", "tool_use");
      node.put("id", call.id());
      node.put("name", call.name());
      JsonNode inputNode =
          parseJsonObject(call.argumentsJson(), "tool_use input must be a JSON object");
      node.set("input", inputNode);
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported assistant block type");
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

  /**
   * 按 Anthropic 原生协议在固定边界写入 {@code cache_control} ephemeral 标记：最后一个 tools 条目、最后一个合格 system
   * block，以及最近历史请求端点；总标记数不超过 {@link #MAX_CACHE_BREAKPOINTS}。会话 {@code key} 不是 Anthropic 原生选项，因此不下发。
   */
  private static void applyCacheMarkers(
      ProviderCacheControl cacheControl,
      ArrayNode toolsArray,
      ArrayNode systemArray,
      List<ObjectNode> cacheEndpoints) {
    if (cacheControl.retention() == PromptCacheRetention.NONE) {
      return;
    }

    ObjectNode marker = NODES.objectNode();
    marker.put("type", "ephemeral");
    if (cacheControl.retention() == PromptCacheRetention.LONG) {
      marker.put("ttl", "1h");
    }

    int markersPlaced = 0;

    // 1. TOOLS: 最后一个合格 tool
    if (!toolsArray.isEmpty()) {
      ((ObjectNode) toolsArray.get(toolsArray.size() - 1)).set("cache_control", marker);
      markersPlaced++;
    }

    // 2. SYSTEM: 最后一个合格 system block
    ObjectNode lastSystemBlock = latestEligibleBlock(systemArray);
    if (lastSystemBlock != null) {
      lastSystemBlock.set("cache_control", marker);
      markersPlaced++;
    }

    // 3. CONVERSATION: 剩余预算用于最近历史请求端点与当前尾部；最近端点优先。
    for (int i = cacheEndpoints.size() - 1; i >= 0 && markersPlaced < MAX_CACHE_BREAKPOINTS; i--) {
      cacheEndpoints.get(i).set("cache_control", marker);
      markersPlaced++;
    }
  }

  /** 候选必须引用最终 wire 中的同一对象；相同文本不是相同端点，连续空/opaque 消息则不重复占预算。 */
  private static void addCacheEndpoint(List<ObjectNode> endpoints, ObjectNode endpoint) {
    if (endpoint != null
        && (endpoints.isEmpty() || endpoints.get(endpoints.size() - 1) != endpoint)) {
      endpoints.add(endpoint);
      if (endpoints.size() > MAX_CACHE_BREAKPOINTS) {
        endpoints.removeFirst();
      }
    }
  }

  /** 保守选择本地支持的官方可缓存块，不猜测未知块或签名块的格式，也不清洗空 text。 */
  private static ObjectNode latestEligibleBlock(JsonNode contents) {
    for (int i = contents.size() - 1; i >= 0; i--) {
      JsonNode block = contents.get(i);
      boolean eligible =
          switch (block.path("type").asText()) {
            case "text" -> block.path("text").isTextual()
                && !block.path("text").textValue().isEmpty();
            case "image", "document", "tool_use", "tool_result" -> true;
            default -> false;
          };
      if (eligible) {
        return (ObjectNode) block;
      }
    }
    return null;
  }

  private static boolean isValidHttpUrl(String source) {
    if (source == null || source.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(source);
      String scheme = uri.getScheme();
      return scheme != null
          && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && uri.getHost() != null
          && !uri.getHost().isBlank();
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String extractBase64Data(String source, String expectedMediaType) {
    if (source == null || source.isBlank()) {
      return null;
    }
    String prefix = "data:" + expectedMediaType + ";base64,";
    if (source.startsWith(prefix)) {
      String data = source.substring(prefix.length());
      if (!data.isBlank()) {
        try {
          byte[] decoded = Base64.getDecoder().decode(data);
          if (decoded.length > 0) {
            return data;
          }
        } catch (IllegalArgumentException ignored) {
          return null;
        }
      }
    }
    return null;
  }

  /** 编码完成的 assistant wire 消息；{@code resetsHistory} 表示该消息的 replay 代表已摘要历史，必须省略此前的 wire messages。 */
  private record EncodedAssistantMessage(ObjectNode node, boolean resetsHistory) {}

  /** replay 判定结果；{@code startsHistory} 为真当且仅当 payload 以 compaction block 开头。 */
  private record ReplayDecision(boolean replayable, ArrayNode content, boolean startsHistory) {

    static final ReplayDecision FALLBACK = new ReplayDecision(false, null, false);
  }
}
