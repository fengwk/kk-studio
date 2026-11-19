package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapabilities;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Chat Completions 协议请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 OpenAI 规范的 UTF-8 请求 JSON 字节数组。请求体以 variant 的 native
 * protocolOptions 为合并基座：运行时所有权字段（model、messages、stream、输出预算、prompt cache、已声明的 reasoning）优先并在 native
 * 声明时冲突，其余官方字段原样保留。
 *
 * <p>assistant replay 只按 format 与 affinity 判断：同 format 时先严格校验 payload 结构与 durable 一致性（损坏或与 durable
 * 矛盾必须 INVALID_REQUEST），affinity 匹配即原样回放 opaque 字段；affinity 失配（不同 provider/model/连接代次）或不兼容 format
 * 时退回语义编码，绝不因为存在 replay 就拒绝请求。
 */
final class OpenAiChatRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

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

  /** 应用层最终 UTF-8 请求体字节上限守卫；默认使用共享的 192 MiB 应用上限。 */
  private final RequestBodySizeGuard bodySizeGuard;

  OpenAiChatRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  OpenAiChatRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  OpenAiChatEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    // variant 的原生协议选项是请求体的合并基座：官方顶层能力无损保留，运行时所有权字段随后覆盖并在 native 声明时冲突
    ObjectNode root = request.variant().protocolOptions().copyOptions();
    rejectRuntimeOwnedNativeFields(root);
    rejectLegacyToolFields(root);
    requireSingleChoice(root);

    root.put("model", request.model().modelId());
    root.put("stream", true);

    applyStreamOptions(root);

    applyReasoningParameters(root, request.model(), request.variant());
    // 输出预算只来自 Model 级 limit.output；官方 o-series 与已验证现代型号使用 max_completion_tokens。
    if (OpenAiChatOfficialCapabilities.isOfficialEndpoint(descriptor)
        && OpenAiChatOfficialCapabilities.usesMaxCompletionTokens(request.model().modelId())) {
      root.put("max_completion_tokens", request.outputTokens());
    } else {
      root.put("max_tokens", request.outputTokens());
    }

    // native 只能声明空 tools；运行时 function tools 构成最终数组
    mergeTools(root, request.tools());

    // 应用 Prompt Cache 策略：key 直接使用运行时提供的会话 key
    applyPromptCache(root, request.cacheControl(), descriptor, request.model().modelId());

    root.set("messages", encodeMessages(request, descriptor));

    byte[] bodyUtf8Bytes = serialize(root);
    bodySizeGuard.enforce(bodyUtf8Bytes);
    return new OpenAiChatEncodedRequest(bodyUtf8Bytes);
  }

  private static byte[] serialize(ObjectNode root) {
    try {
      return OBJECT_MAPPER.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize OpenAI chat request to JSON");
    }
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

  /** Responses 风格的 tools 数组与单次 function 调用无法表达运行时工具，明确拒绝并指向运行时入口。 */
  private static void rejectLegacyToolFields(ObjectNode root) {
    for (String field : List.of("functions", "function_call")) {
      if (root.has(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions "
                + field
                + " is unsupported; bind client tools through runtime ProviderToolDefinition");
      }
    }
  }

  /** 本协议一次只生成一个 choice；native 声明 {@code n} 时必须恰为 1。 */
  private static void requireSingleChoice(ObjectNode root) {
    if (!root.has("n")) {
      return;
    }
    JsonNode n = root.get("n");
    if (!n.isIntegralNumber() || !BigInteger.ONE.equals(n.bigIntegerValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions n must be exactly 1");
    }
  }

  /**
   * {@code stream_options} 由运行时与 native 共同构造：native 必须是 object 且保留自己的子字段，{@code include_usage}
   * 由运行时独占并始终为 true，因为完整 usage 是成功响应的必需事实。
   */
  private static void applyStreamOptions(ObjectNode root) {
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
    streamOptions.put("include_usage", true);
  }

  /**
   * native tools 必须是空 array，运行时 function tools 追加在其后；合并结果写回请求体。
   *
   * <p>native 未声明 tools 且运行时无工具时不产生空的 {@code tools} 字段。
   */
  private static void mergeTools(ObjectNode root, List<ProviderToolDefinition> runtimeTools) {
    JsonNode nativeTools = root.get("tools");
    ArrayNode toolsArray = null;
    if (nativeTools != null) {
      if (!nativeTools.isArray()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "protocolOptions field tools must be an array");
      }
      toolsArray = (ArrayNode) nativeTools;
      if (!toolsArray.isEmpty()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions tools must be empty; bind client tools through runtime ProviderToolDefinition");
      }
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
  }

  private static ArrayNode encodeTools(List<ProviderToolDefinition> tools) {
    if (tools.isEmpty()) {
      return null;
    }
    ArrayNode toolsArray = NODES.arrayNode();
    for (ProviderToolDefinition tool : tools) {
      toolsArray.add(encodeTool(tool));
    }
    return toolsArray;
  }

  private static ObjectNode encodeTool(ProviderToolDefinition tool) {
    ObjectNode toolNode = NODES.objectNode();
    toolNode.put("type", "function");
    ObjectNode functionNode = toolNode.putObject("function");
    functionNode.put("name", tool.name());
    functionNode.put("description", tool.description());
    functionNode.set("parameters", parseToolParameters(tool));
    return toolNode;
  }

  private static JsonNode parseToolParameters(ProviderToolDefinition tool) {
    JsonNode parameters;
    try {
      parameters = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid tool inputSchemaJson for tool: " + tool.name());
    }
    if (!parameters.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "tool inputSchemaJson must be a JSON object: " + tool.name());
    }
    return parameters;
  }

  /**
   * reasoning effort 编码：未声明（null）表示不覆盖服务端默认，此时 native 的 {@code reasoning_effort} / {@code thinking}
   * 属于厂商原生事实，原样保留（如 DeepSeek 在原生选项中声明 thinking）；已声明（含 {@code off}）时这两个字段由运行时独占，native 声明即冲突， 随后写
   * {@code reasoning_effort}。非 reasoning 模型不发送推理字段。
   */
  private static void applyReasoningParameters(
      ObjectNode root, ModelDescriptor model, ModelVariant variant) {
    String effort = variant.reasoningEffort();
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
    root.put("reasoning_effort", off ? "none" : effort);
  }

  /** 系统指令合成为唯一的前导 system message；assistant replay 按 format 与 affinity 决定原样回放或语义 fallback。 */
  private static ArrayNode encodeMessages(ProviderRequest request, ProviderDescriptor descriptor) {
    ArrayNode messages = NODES.arrayNode();
    messages.add(encodeSystemInstruction(request.systemInstruction()));
    for (ProviderMessage message : request.messages()) {
      ObjectNode encoded = encodeMessage(message, descriptor, request.model().modelId());
      messages.add(encoded);
    }
    return messages;
  }

  private static ObjectNode encodeMessage(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    return switch (message.role()) {
      case USER -> OpenAiChatUserMessageEncoder.encode(message);
      case TOOL -> encodeToolMessage(message);
      case ASSISTANT -> OpenAiChatAssistantMessageEncoder.encode(
          message, descriptor, requestedModel);
      case SYSTEM -> throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "mid-conversation system messages are unsupported by OpenAI Chat");
    };
  }

  /** 本协议没有顶层 system 字段，因此把请求唯一的系统指令合成为一条前导 system message。 */
  private static ObjectNode encodeSystemInstruction(String systemInstruction) {
    ObjectNode message = NODES.objectNode();
    message.put("role", "system");
    message.put("content", systemInstruction);
    return message;
  }

  /** TOOL 消息折叠为单条文本：文本与 JSON 结果按顺序拼接，媒体没有 wire 形态。 */
  private static ObjectNode encodeToolMessage(ProviderMessage message) {
    if (message.contents().size() != 1
        || !(message.contents().get(0) instanceof ProviderToolResultBlock resultBlock)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "TOOL message must contain exactly one ProviderToolResultBlock");
    }
    ObjectNode messageNode = NODES.objectNode();
    messageNode.put("role", "tool");
    messageNode.put("tool_call_id", resultBlock.toolCallId());
    messageNode.put("content", flattenToolResult(resultBlock.contents()));
    return messageNode;
  }

  private static String flattenToolResult(List<ProviderContentBlock> contents) {
    StringBuilder text = new StringBuilder();
    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        text.append(textBlock.text());
      } else if (block instanceof ProviderJsonBlock jsonBlock) {
        text.append(jsonBlock.json());
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "TOOL message contains unsupported block: " + block.getClass().getSimpleName());
      }
    }
    return text.toString();
  }

  /** Map cache intent only to OpenAI capabilities proven for the official endpoint and model. */
  private static void applyPromptCache(
      ObjectNode root,
      ProviderCacheControl cacheControl,
      ProviderDescriptor descriptor,
      String modelId) {
    PromptCacheCapabilities capabilities =
        PromptCacheCapabilities.resolve(descriptor.type(), descriptor.endpoint(), modelId);
    if (cacheControl.retention() == PromptCacheRetention.LONG
        && !capabilities.supports(PromptCacheRetention.LONG)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "prompt cache retention LONG is unsupported for this provider model");
    }
    if (!OpenAiChatOfficialCapabilities.isOfficialEndpoint(descriptor)) {
      return;
    }
    boolean modern = capabilities.supports(PromptCacheRetention.NONE);
    Long longTtlSeconds = capabilities.reliableWriteTtlSeconds(PromptCacheRetention.LONG);
    switch (cacheControl.retention()) {
      case NONE -> {
        if (modern) {
          root.putObject("prompt_cache_options").put("mode", "explicit");
        }
      }
      case SHORT -> root.put("prompt_cache_key", cacheControl.key());
      case LONG -> {
        if (longTtlSeconds != null) {
          root.put("prompt_cache_key", cacheControl.key());
          root.put("prompt_cache_retention", "24h");
        }
      }
    }
  }
}
