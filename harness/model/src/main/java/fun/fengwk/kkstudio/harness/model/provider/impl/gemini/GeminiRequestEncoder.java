package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Google AI Gemini GenerateContent 协议请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 Gemini streamGenerateContent 规范的 UTF-8 JSON 字节数组。
 *
 * <p>assistant replay 从有序 blocks 的 Provider 元数据重建 Gemini parts（含 {@code thoughtSignature} 与
 * functionCall 扩展）；只有同 protocol、provider/model affinity 匹配且 block 语义与 durable 内容一致时原样回放。否则按 durable
 * 语义编码；跨模型工具签名不会保留。
 */
final class GeminiRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> HOSTED_TOOL_KEYS =
      Set.of(
          "googleSearch",
          "googleSearchRetrieval",
          "retrieval",
          "codeExecution",
          "urlContext",
          "googleMaps");

  /** 应用层最终 UTF-8 请求体字节上限守卫；默认使用共享的 192 MiB 应用上限。 */
  private final RequestBodySizeGuard bodySizeGuard;

  GeminiRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  GeminiRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  GeminiEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    validateCacheControl(request.cacheControl());

    // 1. 以 variant 原生协议选项为 root：非 runtime-owned 的官方字段无损保留
    ObjectNode root = request.variant().protocolOptions().copyOptions();
    rejectRuntimeOwnedOptions(root);

    // 2. 请求唯一的系统指令映射为顶层 systemInstruction.parts
    ObjectNode systemInstruction = NODES.objectNode();
    ArrayNode sysParts = systemInstruction.putArray("parts");
    ObjectNode systemPart = sysParts.addObject();
    systemPart.put("text", request.systemInstruction());
    root.set("systemInstruction", systemInstruction);

    // 3. generationConfig：保留原生官方子字段，再写 runtime-owned 输出预算与 thinkingConfig
    root.set("generationConfig", mergeGenerationConfig(root, request));

    // 4. tools：原生 hosted tool objects无损保留，仅追加一个 runtime functionDeclarations tool
    ArrayNode toolsArray = mergeTools(root, request.tools());

    // 5. contents 映射与 prefix hash 维护（严格按当前实际构建的、已合并相邻同 role 的 wire contents 计算）
    ArrayNode contentsArray = root.putArray("contents");
    GeminiWireContents.encode(
        contentsArray, request.messages(), descriptor, request.model().modelId());

    if (contentsArray.size() == 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "Gemini contents must contain at least one message");
    }

    try {
      byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(root);
      bodySizeGuard.enforce(bytes);
      return new GeminiEncodedRequest(bytes);
    } catch (JacksonException e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize Gemini request body JSON");
    }
  }

  private static void validateCacheControl(ProviderCacheControl cacheControl) {
    if (cacheControl.retention() != PromptCacheRetention.NONE) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini only supports automatic prompt caching; explicit cacheControl is not allowed");
    }
  }

  /**
   * native options 绝不能覆盖 runtime-owned 请求事实：{@code contents}、{@code systemInstruction} 与 {@code
   * cachedContent} 出现即冲突，错误消息 只说明被违反的所有权约束，绝不回显 value。
   */
  private static void rejectRuntimeOwnedOptions(ObjectNode root) {
    if (root.has("contents")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options must not override runtime-owned contents");
    }
    if (root.has("systemInstruction")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options must not override runtime-owned systemInstruction");
    }
    if (root.has("cachedContent")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options must not override runtime-owned cachedContent");
    }
  }

  /**
   * 合并 generationConfig：native 值必须是 object
   * 且其官方子字段（sampling、stop、responseMimeType/Schema/JsonSchema、mediaResolution、
   * speechConfig、imageConfig、routingConfig 等）原样保留；{@code maxOutputTokens} 始终
   * runtime-owned，出现即冲突；variant 声明 reasoningEffort 时 {@code thinkingConfig} 同样 runtime-owned，出现即冲突。
   */
  private static ObjectNode mergeGenerationConfig(ObjectNode root, ProviderRequest request) {
    JsonNode nativeNode = root.get("generationConfig");
    ObjectNode genConfig;
    if (nativeNode == null) {
      genConfig = NODES.objectNode();
    } else if (nativeNode instanceof ObjectNode nativeConfig) {
      genConfig = nativeConfig;
    } else {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options generationConfig must be a JSON object");
    }

    if (genConfig.has("maxOutputTokens")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options must not override runtime-owned generationConfig.maxOutputTokens");
    }
    if (genConfig.has("candidateCount")) {
      JsonNode count = genConfig.get("candidateCount");
      if (!count.isIntegralNumber() || !count.bigIntegerValue().equals(BigInteger.ONE)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions generationConfig.candidateCount must be exactly 1");
      }
    }

    // variant 声明 effort 时 thinkingConfig 由 runtime 独占；未声明时保留 native model-specific configuration。
    String reasoningEffort = request.variant().reasoningEffort();
    if (reasoningEffort != null && genConfig.has("thinkingConfig")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options must not declare thinkingConfig when variant reasoningEffort is set");
    }

    // 输出预算只来自 Model 级 limit.output（普通请求再按剩余上下文收敛），不来自 variant。
    genConfig.put("maxOutputTokens", request.outputTokens());

    if (reasoningEffort != null) {
      encodeThinkingConfig(genConfig.putObject("thinkingConfig"), request);
    }

    return genConfig;
  }

  /** Use only the thinking control documented for a recognized Gemini model family. */
  private static void encodeThinkingConfig(ObjectNode thinkingConfig, ProviderRequest request) {
    String modelId = request.model().modelId().toLowerCase(Locale.ROOT);
    String effort = request.variant().reasoningEffort();
    if (isGemini25(modelId)) {
      if (request.variant().reasoningOff() && isGemini25Pro(modelId)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "Gemini 2.5 Pro cannot disable thinking; its minimum thinking budget is 128");
      }
      thinkingConfig.put("includeThoughts", !request.variant().reasoningOff());
      thinkingConfig.put(
          "thinkingBudget", request.variant().reasoningOff() ? 0 : thinkingBudget(modelId, effort));
      return;
    }

    Set<String> thinkingLevels = supportedThinkingLevels(modelId);
    if (thinkingLevels != null) {
      if (request.variant().reasoningOff()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "this Gemini model family does not support disabling thinking");
      }
      if (!thinkingLevels.contains(effort)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "reasoningEffort is not supported by this Gemini model");
      }
      thinkingConfig.put("includeThoughts", true);
      thinkingConfig.put("thinkingLevel", effort.toUpperCase(Locale.ROOT));
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "reasoningEffort has no verified thinking wire contract for this Gemini model");
  }

  private static boolean isGemini25(String modelId) {
    return modelId.matches("gemini-2\\.5-(?:pro|flash|flash-lite)(?:-.+)?");
  }

  private static boolean isGemini25Pro(String modelId) {
    return modelId.matches("gemini-2\\.5-pro(?:-.+)?");
  }

  /** Return only levels verified for a documented Gemini 3+ model name. */
  private static Set<String> supportedThinkingLevels(String modelId) {
    return switch (modelId) {
      case "gemini-3-pro-preview", "gemini-3-pro-preview-customtools" -> Set.of("low", "high");
      case "gemini-3-flash-preview",
          "gemini-3.1-flash-lite",
          "gemini-3.1-flash-lite-preview",
          "gemini-3.5-flash",
          "gemini-3.5-flash-lite",
          "gemini-3.6-flash" -> Set.of("minimal", "low", "medium", "high");
      case "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools" -> Set.of(
          "low", "medium", "high");
      case "gemini-3.1-flash-lite-image" -> Set.of("minimal", "high");
      case "gemini-3.7-flash", "gemini-3.8-flash", "gemini-4-argon", "gemini-4-carbon" -> Set.of(
          "low", "medium", "high");
      case "gemini-4-neon", "gemini-4-hydrogen" -> Set.of("low", "medium", "high", "xhigh");
      default -> null;
    };
  }

  private static int thinkingBudget(String modelId, String effort) {
    if (effort.equals("minimal")) {
      return modelId.contains("flash-lite") ? 512 : 128;
    }
    return switch (effort) {
      case "low" -> 2048;
      case "medium" -> 8192;
      case "high" -> modelId.contains("pro") ? 32768 : 24576;
      default -> throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "reasoningEffort is not a supported Gemini 2.5 thinking budget level");
    };
  }

  /**
   * 合并 tools：native 值必须是 array 且其 hosted tool
   * objects（googleSearch/codeExecution/urlContext/retrieval 等）原样保留， 之后只追加一个 runtime
   * functionDeclarations tool object；合并结果参与 prefix hash 与缓存前缀处理。
   */
  private static ArrayNode mergeTools(ObjectNode root, List<ProviderToolDefinition> tools) {
    JsonNode nativeNode = root.get("tools");
    ArrayNode toolsArray;
    if (nativeNode == null) {
      if (tools.isEmpty()) {
        return null;
      }
      toolsArray = root.putArray("tools");
    } else if (nativeNode instanceof ArrayNode nativeTools) {
      toolsArray = nativeTools;
      for (int i = 0; i < nativeTools.size(); i++) {
        JsonNode nativeTool = nativeTools.get(i);
        if (!nativeTool.isObject()
            || nativeTool.size() != 1
            || !HOSTED_TOOL_KEYS.contains(nativeTool.propertyNames().iterator().next())
            || !nativeTool.values().iterator().next().isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools["
                  + i
                  + "] must be a hosted tool object; bind client tools through runtime ProviderToolDefinition");
        }
      }
    } else {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "Gemini protocol options tools must be a JSON array");
    }

    if (tools.isEmpty()) {
      return toolsArray;
    }

    ObjectNode toolObj = toolsArray.addObject();
    ArrayNode fnDecls = toolObj.putArray("functionDeclarations");

    for (ProviderToolDefinition tool : tools) {
      ObjectNode fn = fnDecls.addObject();
      fn.put("name", tool.name());
      if (tool.description() != null && !tool.description().isBlank()) {
        fn.put("description", tool.description());
      }
      JsonNode schemaNode =
          ProviderJsonHelper.parseStrictJson(
              tool.inputSchemaJson(), "tool inputSchemaJson is not valid JSON");
      if (!schemaNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool parameters must be a JSON object");
      }
      fn.set("parameters", schemaNode);
    }
    return toolsArray;
  }
}
