package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemHostedToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolRemovalBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderMediaValidator;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Anthropic Messages 协议的请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 Anthropic 规范的 UTF-8 请求 JSON 字节数组。
 *
 * <p>请求体以 variant 的 native {@code protocolOptions} 为合并起点：运行时所有权字段 （{@code model}/{@code
 * max_tokens}/{@code stream}/{@code messages}/{@code system}/{@code cache_control}） 冲突显式失败，{@code
 * tools} 保留 native 条目后追加 runtime 工具，其余官方顶层字段无损保留；cache marker 与请求体大小均基于最终合并结果。
 *
 * <p>显式 adaptive effort 时，{@code anthropic-beta} 包含消息级 effort 所需的三个 beta。
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
public final class AnthropicRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  /** 语义比对专用读取器：数值按 scale 无关归一，只用于 replay 与 durable 的一致性比对，不参与 wire 输出。 */
  private static final ObjectReader COMPARISON_READER =
      OBJECT_MAPPER.reader().with(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /**
   * 运行时独占的顶层请求事实：{@code cache_control} 表示 prompt cache 的运行时所有权（标记由本编码器写入
   * system/tools/messages），native options 一律不得声明，冲突显式失败。
   */
  private static final List<String> RUNTIME_OWNED_OPTION_FIELDS =
      List.of("model", "max_tokens", "stream", "messages", "system", "cache_control");

  private static final int MAX_CACHE_BREAKPOINTS = 4;
  private static final String PER_TURN_BETA = "per-turn-control-2026-07-01";
  private static final String MID_CONVERSATION_BETA = "mid-conversation-system-2026-04-07";
  private static final String EFFORT_BETA = "effort-2025-11-24";
  private static final String INLINE_TOOLS_BETA = "inline-tools-2026-09-15";
  private static final String DEFERRED_PLACEHOLDER_NAME = "__kk_deferred_placeholder__";

  private final RequestBodySizeGuard bodySizeGuard;

  public AnthropicRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  AnthropicRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  public AnthropicEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    // native protocolOptions 是合并起点：运行时所有权字段由本编码器写入，其余官方顶层字段原样保留
    ObjectNode root = request.variant().protocolOptions().copyOptions();
    rejectRuntimeOwnedOptions(root);

    root.put("model", request.model().modelId());

    int maxTokens = request.outputTokens();
    root.put("max_tokens", maxTokens);
    root.put("stream", true);

    applyReasoningParameters(root, request);

    boolean perTurnEffort =
        request.model().reasoning()
            && request.variant() != null
            && request.variant().reasoningEffort() != null
            && !request.variant().reasoningOff();
    boolean deferredToolLane =
        request.model().midConversationSystemMessages() && request.baselineHasClientTools();
    if (request.baselineHasClientTools() && !deferredToolLane) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "client tool baseline requires mid-conversation system-message capability");
    }
    boolean hasInlineToolChanges = hasInlineToolChanges(request.messages());
    List<String> betaFeatures =
        resolveBetaFeatures(perTurnEffort, deferredToolLane || hasInlineToolChanges);

    ArrayNode toolsArray = mergeTools(root, request.tools(), deferredToolLane);

    // 系统指令是请求唯一的顶层 system；编码为单块数组以便在 SYSTEM 边界打缓存标记。
    ArrayNode unmarkedSystemArray = NODES.arrayNode();
    ObjectNode systemBlock = NODES.objectNode();
    systemBlock.put("type", "text");
    systemBlock.put("text", request.systemInstruction());
    unmarkedSystemArray.add(systemBlock);

    // 从左到右构建 wire messages，并记录可用于缓存标记的消息端点
    ArrayNode wireMessagesArray = NODES.arrayNode();
    List<String> messageEfforts = new ArrayList<>();
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
          messageEfforts.clear();
          cacheEndpoints.clear();
          lastEligible = null;
        }
        wireMessagesArray.add(assistant.node());
      } else if (msg.role() == ProviderMessageRole.USER) {
        wireMessagesArray.add(encodeUserMessage(msg));
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        wireMessagesArray.add(encodeToolMessage(msg));
      } else if (msg.role() == ProviderMessageRole.SYSTEM) {
        addCacheEndpoint(cacheEndpoints, lastEligible);
        wireMessagesArray.add(encodeSystemMessage(msg));
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unexpected message role: " + msg.role());
      }
      messageEfforts.add(msg.reasoningEffort());
      ObjectNode messageEndpoint =
          latestEligibleBlock(wireMessagesArray.get(wireMessagesArray.size() - 1).path("content"));
      if (messageEndpoint != null) {
        lastEligible = messageEndpoint;
      }
    }
    addCacheEndpoint(cacheEndpoints, lastEligible);

    // 注入 cache marker
    applyCacheMarkers(
        request.cacheControl(), toolsArray, unmarkedSystemArray, cacheEndpoints, deferredToolLane);
    if (perTurnEffort) {
      wireMessagesArray =
          applyPerTurnEffort(
              wireMessagesArray, messageEfforts, request.variant().reasoningEffort());
    }

    if (!toolsArray.isEmpty()) {
      root.set("tools", toolsArray);
    }
    root.set("system", unmarkedSystemArray);
    root.set("messages", wireMessagesArray);

    byte[] utf8Bytes;
    try {
      utf8Bytes = OBJECT_MAPPER.writeValueAsBytes(root);
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize Anthropic request body");
    }

    bodySizeGuard.enforce(utf8Bytes);

    return new AnthropicEncodedRequest(utf8Bytes, betaFeatures);
  }

  private static boolean hasInlineToolChanges(List<ProviderMessage> messages) {
    for (ProviderMessage message : messages) {
      if (message.role() != ProviderMessageRole.SYSTEM) {
        continue;
      }
      for (ProviderContentBlock content : message.contents()) {
        if (content instanceof ProviderSystemToolAdditionBlock
            || content instanceof ProviderSystemHostedToolAdditionBlock
            || content instanceof ProviderSystemToolRemovalBlock) {
          return true;
        }
      }
    }
    return false;
  }

  /** Add the beta support required by the selected mid-conversation request features. */
  private static List<String> resolveBetaFeatures(boolean perTurnEffort, boolean inlineTools) {
    List<String> betaFeatures = new ArrayList<>();
    if (perTurnEffort) {
      betaFeatures.add(MID_CONVERSATION_BETA);
      betaFeatures.add(PER_TURN_BETA);
      betaFeatures.add(EFFORT_BETA);
    }
    if (inlineTools) {
      betaFeatures.add(INLINE_TOOLS_BETA);
    }
    return List.copyOf(betaFeatures);
  }

  /** 从最终保留的消息反向补齐未知等级，再在连续 user/tool 消息之后声明变化。 */
  static ArrayNode applyPerTurnEffort(
      ArrayNode messages, List<String> efforts, String currentEffort) {
    String[] levels = new String[messages.size()];
    String carry = currentEffort;
    for (int i = messages.size() - 1; i >= 0; i--) {
      String effort = efforts.get(i);
      if ("assistant".equals(messages.get(i).path("role").asString())
          && effort != null
          && !effort.equals("off")
          && !effort.isBlank()) {
        carry = effort;
      }
      levels[i] = carry;
    }
    ArrayNode result = NODES.arrayNode();
    String declared = null;
    String pending = null;
    for (int i = 0; i < messages.size(); i++) {
      ObjectNode message = (ObjectNode) messages.get(i);
      String role = message.path("role").asString();
      if (pending != null && !role.equals("user")) {
        declared = pending;
        if (role.equals("system")) {
          JsonNode existing = message.get("output_config");
          ObjectNode outputConfig =
              existing instanceof ObjectNode object ? object : message.putObject("output_config");
          outputConfig.put("effort", pending);
          result.add(message);
          pending = null;
          continue;
        }
        result.add(effortControl(pending));
        pending = null;
      }
      result.add(message);
      if (role.equals("user")) {
        pending = Objects.equals(levels[i], declared) ? null : levels[i];
      }
    }
    if (pending != null) {
      result.add(effortControl(pending));
    }
    return result;
  }

  private static ObjectNode effortControl(String effort) {
    ObjectNode node = NODES.objectNode();
    node.put("role", "system");
    node.putArray("content");
    node.putObject("output_config").put("effort", effort);
    return node;
  }

  /**
   * reasoning effort 编码：{@code off} 显式关闭推理，发送 {@code thinking:{type:"disabled"}}； 非空 effort 默认以
   * ADAPTIVE 模式发送 {@code thinking:{type:"adaptive",display:"summarized"}} 与 {@code
   * output_config:{effort:...}}；null 不声明推理字段，由服务端默认决定，此时 native {@code thinking}/{@code
   * output_config} 原样保留（如预算思考在原生选项中声明 thinking）。
   *
   * <p>一旦 runtime 要写入 {@code thinking}，native {@code thinking} 必须显式拒绝；{@code output_config} 只对
   * runtime 同样要写的 {@code effort} 子字段拒绝，其余官方子字段无损保留。
   */
  private static void applyReasoningParameters(ObjectNode root, ProviderRequest request) {
    if (!request.model().reasoning()
        || request.variant() == null
        || request.variant().reasoningEffort() == null) {
      return;
    }

    if (root.has("thinking")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions must not override runtime field: thinking");
    }

    if (request.variant().reasoningOff()) {
      ObjectNode thinking = root.putObject("thinking");
      thinking.put("type", "disabled");
      return;
    }

    String effort = request.variant().reasoningEffort();
    ObjectNode thinking = root.putObject("thinking");
    thinking.put("type", "adaptive");
    thinking.put("display", "summarized");

    ObjectNode outputConfig = mergeOutputConfig(root);
    outputConfig.put("effort", effort);
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
  private static ArrayNode mergeTools(
      ObjectNode root,
      List<ProviderToolDefinition> runtimeTools,
      boolean appendDeferredPlaceholder) {
    JsonNode nativeTools = root.get("tools");
    if (nativeTools != null && !nativeTools.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions tools must be a JSON array");
    }

    ArrayNode merged = NODES.arrayNode();
    Set<String> names = new HashSet<>();
    if (nativeTools != null) {
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
            || !type.isString()
            || !type.stringValue().matches("(web_search|web_fetch|code_execution)_[0-9]{8}")
            || !name.isString()
            || name.stringValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools["
                  + i
                  + "] must be a supported hosted tool; bind client tools through runtime"
                  + " ProviderToolDefinition");
        }
        if (!names.add(name.stringValue())) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools[" + i + "] duplicates a tool name");
        }
        for (ProviderToolDefinition runtimeTool : runtimeTools) {
          if (name.stringValue().equals(runtimeTool.name())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "protocolOptions tools[" + i + "] name conflicts with runtime tool");
          }
        }
        merged.add(nativeTool.deepCopy());
      }
    }
    for (ProviderToolDefinition tool : runtimeTools) {
      if (appendDeferredPlaceholder && DEFERRED_PLACEHOLDER_NAME.equals(tool.name())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "runtime tools must not use the reserved deferred placeholder name");
      }
      merged.add(encodeTool(tool));
    }
    if (appendDeferredPlaceholder) {
      if (!names.add(DEFERRED_PLACEHOLDER_NAME)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "runtime tools must not use the reserved deferred placeholder name");
      }
      merged.add(encodeDeferredPlaceholder());
    }
    return merged;
  }

  private static ObjectNode encodeDeferredPlaceholder() {
    ObjectNode placeholder = NODES.objectNode();
    placeholder.put("name", DEFERRED_PLACEHOLDER_NAME);
    placeholder.put("description", "Reserved placeholder. Never available. Never call this.");
    ObjectNode inputSchema = placeholder.putObject("input_schema");
    inputSchema.put("type", "object");
    inputSchema.putObject("properties");
    inputSchema.putArray("required");
    placeholder.put("defer_loading", true);
    return placeholder;
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
    } catch (JacksonException exception) {
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

  private static ObjectNode encodeSystemMessage(ProviderMessage message) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "system");
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderTextBlock textBlock) {
        ObjectNode text = NODES.objectNode();
        text.put("type", "text");
        text.put("text", textBlock.text());
        contents.add(text);
      } else if (block instanceof ProviderSystemToolAdditionBlock addition) {
        contents.add(encodeToolAddition(encodeTool(addition.tool())));
      } else if (block instanceof ProviderSystemHostedToolAdditionBlock addition) {
        contents.add(encodeToolAddition(parseHostedToolDefinition(addition.definitionJson())));
      } else if (block instanceof ProviderSystemToolRemovalBlock removal) {
        ObjectNode tool = NODES.objectNode();
        tool.put("type", "tool_reference");
        tool.put("name", removal.name());
        ObjectNode node = NODES.objectNode();
        node.put("type", "tool_removal");
        node.set("tool", tool);
        contents.add(node);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported content in Anthropic system message: " + block.getClass().getSimpleName());
      }
    }
    return msgNode;
  }

  private static ObjectNode encodeToolAddition(ObjectNode definition) {
    ObjectNode tool = NODES.objectNode();
    tool.put("type", "tool_definition");
    tool.set("definition", definition);
    ObjectNode addition = NODES.objectNode();
    addition.put("type", "tool_addition");
    addition.set("tool", tool);
    return addition;
  }

  private static ObjectNode parseHostedToolDefinition(String definitionJson) {
    try {
      JsonNode definition = OBJECT_MAPPER.readTree(definitionJson);
      if (definition.isObject() && definition.path("name").isString()) {
        return (ObjectNode) definition;
      }
    } catch (JacksonException ignored) {
      // The durable source is validated as an object when materialized.
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "hosted tool definition must be a JSON object with a name");
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
    if (!ProviderMediaValidator.isAllowedMediaType(
        block.mediaType(), ProviderMediaValidator.IMAGE_MEDIA_TYPES)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported image media type");
    }
    ObjectNode node = NODES.objectNode();
    node.put("type", "image");
    ObjectNode sourceNode = node.putObject("source");
    String base64Data = ProviderMediaValidator.extractBase64Data(block.source(), block.mediaType());
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
    if (!ProviderMediaValidator.isAllowedMediaType(
        block.mediaType(), ProviderMediaValidator.DOCUMENT_MEDIA_TYPES)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported document media type");
    }
    String base64Data = ProviderMediaValidator.extractBase64Data(block.source(), block.mediaType());
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

    ReplayDecision replay = evaluateBlockReplay(message, descriptor, requestedModel);
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
      ObjectNode fallback = encodeAssistantFallbackBlock(block);
      if (fallback != null) {
        contents.add(fallback);
      }
    }
    return new EncodedAssistantMessage(msgNode, false);
  }

  private static ReplayDecision evaluateBlockReplay(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    if (message.contents().isEmpty()) {
      return ReplayDecision.FALLBACK;
    }
    ArrayNode content = NODES.arrayNode();
    for (ProviderContentBlock block : message.contents()) {
      ProviderBlockData data = blockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.ANTHROPIC_MESSAGES
          || !data.affinity().equals(descriptor.affinity(requestedModel))) {
        return ReplayDecision.FALLBACK;
      }
      ObjectNode nativeBlock = NODES.objectNode();
      if (block instanceof ProviderTextBlock textBlock) {
        nativeBlock.put("type", "text");
        nativeBlock.put("text", textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        if (data.signature() == null || data.signature().isBlank()) {
          return ReplayDecision.FALLBACK;
        }
        nativeBlock.put("type", "thinking");
        nativeBlock.put("thinking", thinkingBlock.thinking());
        nativeBlock.put("signature", data.signature());
      } else if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
        nativeBlock.put("type", "redacted_thinking");
        nativeBlock.put("data", redactedBlock.data());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        nativeBlock.put("type", "tool_use");
        nativeBlock.put("id", call.id());
        nativeBlock.put("name", call.name());
        nativeBlock.set(
            "input",
            ProviderJsonHelper.parseStrictJsonObject(
                call.argumentsJson(), "tool_use input must be a JSON object"));
      } else if (block instanceof ProviderOpaqueBlock opaqueBlock) {
        nativeBlock.put("type", opaqueBlock.blockType());
      } else {
        return ReplayDecision.FALLBACK;
      }
      if (!mergeNativeFields(nativeBlock, data)) {
        return ReplayDecision.FALLBACK;
      }
      content.add(nativeBlock);
    }
    boolean startsHistory = validatePayloadAgainstDurable(content, message.contents());
    return new ReplayDecision(true, content, startsHistory);
  }

  private static ProviderBlockData blockData(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      return textBlock.providerBlockData();
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      return thinkingBlock.providerBlockData();
    }
    if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
      return redactedBlock.providerBlockData();
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      return toolCallBlock.providerBlockData();
    }
    if (block instanceof ProviderOpaqueBlock opaqueBlock) {
      return opaqueBlock.providerBlockData();
    }
    return null;
  }

  private static boolean mergeNativeFields(ObjectNode target, ProviderBlockData data) {
    JsonNode fields = data.nativeFields();
    for (var entry : fields.properties()) {
      if (target.has(entry.getKey())) {
        return false;
      }
      target.set(entry.getKey(), entry.getValue().deepCopy());
    }
    return true;
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
    List<String> payloadRedacted = new ArrayList<>();

    int compactionIndex = -1;
    int blockIndex = 0;

    for (JsonNode item : contentArray) {
      if (!item.isObject() || !item.has("type") || !item.get("type").isString()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload block");
      }
      String type = item.get("type").stringValue();
      if (type.isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload block");
      }
      switch (type) {
        case "text" -> {
          if (!item.has("text") || !item.get("text").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
          }
          validateCitations(item.get("citations"));
          payloadText.append(item.get("text").stringValue());
        }
        case "thinking" -> {
          if (!item.has("thinking")
              || !item.get("thinking").isString()
              || !item.has("signature")
              || !item.get("signature").isString()
              || item.get("signature").stringValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid thinking block in replay payload");
          }
          payloadThinking.append(item.get("thinking").stringValue());
        }
        case "redacted_thinking" -> {
          if (!item.has("data")
              || !item.get("data").isString()
              || item.get("data").stringValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid redacted_thinking block in replay payload");
          }
          payloadRedacted.add(item.get("data").stringValue());
        }
        case "tool_use" -> {
          if (!item.has("id")
              || !item.get("id").isString()
              || item.get("id").stringValue().isBlank()
              || !item.has("name")
              || !item.get("name").isString()
              || item.get("name").stringValue().isBlank()
              || !item.has("input")
              || !item.get("input").isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid tool_use block in replay payload");
          }
          payloadCalls.add(
              new ProviderToolCall(
                  item.get("id").stringValue(),
                  item.get("name").stringValue(),
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
    List<String> durableRedacted = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock pb) {
        durableThinking.append(pb.thinking());
      } else if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
        durableRedacted.add(redactedBlock.data());
      } else if (block instanceof ProviderOpaqueBlock) {
        // opaque blocks are preserved for the same protocol but have no cross-model semantic
        // projection
      } else if (block instanceof ProviderToolCallBlock cb) {
        ProviderToolCall call = cb.toolCall();
        ProviderJsonHelper.parseStrictJsonObject(
            call.argumentsJson(), "durable tool call arguments must be a JSON object");
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
    if (!durableRedacted.isEmpty() && !payloadRedacted.equals(durableRedacted)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload redacted thinking does not match durable content");
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
      // 合法性边界不变：两侧参数仍按 wire 同款严格对象校验。
      ProviderJsonHelper.parseStrictJsonObject(
          pCall.argumentsJson(), "replay tool call input must be a JSON object");
      ProviderJsonHelper.parseStrictJsonObject(
          dCall.argumentsJson(), "durable tool call arguments must be a JSON object");
      // 语义一致性按数值 scale 无关比对（1.0 与 1.00 同值）；比较专用 reader 只读取比较树，
      // wire 仍使用 replay 原生 input，输出形态不被改写。
      if (!COMPARISON_READER
          .readTree(pCall.argumentsJson())
          .equals(COMPARISON_READER.readTree(dCall.argumentsJson()))) {
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
      if (thinkingBlock.thinking().isEmpty()) {
        return null;
      }
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", thinkingBlock.thinking());
      return node;
    }
    if (block instanceof ProviderRedactedThinkingBlock || block instanceof ProviderOpaqueBlock) {
      return null;
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
          ProviderJsonHelper.parseStrictJsonObject(
              call.argumentsJson(), "tool_use input must be a JSON object");
      node.set("input", inputNode);
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported assistant block type");
  }

  /**
   * 按 Anthropic 原生协议在固定边界写入 {@code cache_control} ephemeral 标记：最后一个 tools 条目、最后一个合格 system
   * block，以及最近历史请求端点；总标记数不超过 {@link #MAX_CACHE_BREAKPOINTS}。会话 {@code key} 不是 Anthropic 原生选项，因此不下发。
   */
  private static void applyCacheMarkers(
      ProviderCacheControl cacheControl,
      ArrayNode toolsArray,
      ArrayNode systemArray,
      List<ObjectNode> cacheEndpoints,
      boolean hasDeferredPlaceholder) {
    if (cacheControl.retention() == PromptCacheRetention.NONE) {
      return;
    }

    ObjectNode marker = NODES.objectNode();
    marker.put("type", "ephemeral");
    if (cacheControl.retention() == PromptCacheRetention.LONG) {
      marker.put("ttl", "1h");
    }

    int markersPlaced = 0;

    // 1. TOOLS: 最后一个实际可用 tool，不能标记 encoder-only deferred placeholder。
    int lastRealToolIndex = toolsArray.size() - (hasDeferredPlaceholder ? 2 : 1);
    if (lastRealToolIndex >= 0) {
      ((ObjectNode) toolsArray.get(lastRealToolIndex)).set("cache_control", marker);
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
          switch (block.path("type").asString("")) {
            case "text" -> block.path("text").isString()
                && !block.path("text").stringValue().isEmpty();
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

  /** 编码完成的 assistant wire 消息；{@code resetsHistory} 表示该消息的 replay 代表已摘要历史，必须省略此前的 wire messages。 */
  private record EncodedAssistantMessage(ObjectNode node, boolean resetsHistory) {}

  /** replay 判定结果；{@code startsHistory} 为真当且仅当 payload 以 compaction block 开头。 */
  private record ReplayDecision(boolean replayable, ArrayNode content, boolean startsHistory) {

    static final ReplayDecision FALLBACK = new ReplayDecision(false, null, false);
  }
}
