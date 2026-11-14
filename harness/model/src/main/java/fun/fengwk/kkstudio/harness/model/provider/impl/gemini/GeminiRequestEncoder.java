package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

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
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
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

  /** 语义比对专用读取器：数值按 scale 无关归一，只用于 replay 与 durable 的一致性比对，不参与 wire 输出。 */
  private static final ObjectReader COMPARISON_READER =
      OBJECT_MAPPER.reader().with(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

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

    String currentRole = null;
    ArrayNode currentParts = null;

    for (int i = 0; i < request.messages().size(); i++) {
      ProviderMessage msg = request.messages().get(i);

      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        String wireRole = "model";

        if (currentParts == null || !wireRole.equals(currentRole)) {
          ObjectNode contentNode = contentsArray.addObject();
          contentNode.put("role", wireRole);
          currentRole = wireRole;
          currentParts = contentNode.putArray("parts");
        }

        List<ObjectNode> blockReplayParts =
            buildBlockReplayParts(descriptor, request.model().modelId(), msg.contents());
        if (blockReplayParts != null) {
          for (ObjectNode partNode : blockReplayParts) {
            currentParts.add(partNode);
          }
        } else {
          for (ProviderContentBlock block : msg.contents()) {
            encodeAssistantBlock(currentParts, block);
          }
        }
      } else { // USER or TOOL
        String wireRole = "user";

        if (currentParts == null || !wireRole.equals(currentRole)) {
          ObjectNode contentNode = contentsArray.addObject();
          contentNode.put("role", wireRole);
          currentRole = wireRole;
          currentParts = contentNode.putArray("parts");
        }

        if (msg.role() == ProviderMessageRole.USER) {
          for (ProviderContentBlock block : msg.contents()) {
            GeminiContentEncoder.encodeUserBlock(currentParts, block);
          }
        } else { // TOOL
          for (ProviderContentBlock block : msg.contents()) {
            if (block instanceof ProviderToolResultBlock resultBlock) {
              GeminiContentEncoder.encodeToolResultBlock(currentParts, resultBlock);
            }
          }
        }
      }
    }

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
    if (cacheControl != null && cacheControl.retention() != PromptCacheRetention.NONE) {
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

    // reasoning effort 映射到 thinkingConfig：未声明时不生成，off 显式关闭，其余级别下发生效级别
    String reasoningEffort = request.variant().reasoningEffort();
    if (reasoningEffort != null && genConfig.has("thinkingConfig")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini protocol options must not declare thinkingConfig when variant reasoningEffort is set");
    }

    // 输出预算只来自 Model 级 limit.output（普通请求再按剩余上下文收敛），不来自 variant。
    genConfig.put("maxOutputTokens", request.outputTokens());

    if (reasoningEffort != null) {
      ObjectNode thinkingConfig = genConfig.putObject("thinkingConfig");
      if (request.variant().reasoningOff()) {
        thinkingConfig.put("includeThoughts", false);
        thinkingConfig.put("thinkingBudget", 0);
      } else {
        thinkingConfig.put("includeThoughts", true);
        thinkingConfig.put("thinkingLevel", reasoningEffort);
      }
    }

    return genConfig;
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
      if (tools == null || tools.isEmpty()) {
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

    if (tools == null || tools.isEmpty()) {
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

  private static List<ObjectNode> buildBlockReplayParts(
      ProviderDescriptor descriptor, String requestedModel, List<ProviderContentBlock> blocks) {
    if (blocks.isEmpty()) {
      return null;
    }
    var requestedAffinity = descriptor.affinity(requestedModel);
    List<ObjectNode> parts = new ArrayList<>();
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "model");
    ArrayNode payloadParts = payload.putArray("parts");

    for (ProviderContentBlock block : blocks) {
      ProviderBlockData data = providerBlockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.GEMINI_CONTENT
          || !data.affinity().equals(requestedAffinity)) {
        return null;
      }

      ObjectNode part = NODES.objectNode();
      JsonNode functionCallExtras = null;
      ObjectNode nativeFields = (ObjectNode) data.nativeFields();
      if (block instanceof ProviderToolCallBlock) {
        functionCallExtras = nativeFields.remove("functionCall");
      }
      mergeNativeFields(part, nativeFields);
      if (block instanceof ProviderThinkingBlock thinkingBlock) {
        part.put("text", thinkingBlock.thinking());
        part.put("thought", data.thought() == null || data.thought().booleanValue());
      } else if (block instanceof ProviderTextBlock textBlock) {
        part.put("text", textBlock.text());
        if (data.thought() != null) {
          part.put("thought", data.thought().booleanValue());
        }
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        ObjectNode functionCall = part.putObject("functionCall");
        if (functionCallExtras != null) {
          mergeNativeFields(functionCall, functionCallExtras);
        }
        functionCall.put("name", call.name());
        if (call.id() != null && !call.id().isBlank()) {
          functionCall.put("id", call.id());
        }
        JsonNode args =
            ProviderJsonHelper.parseStrictJson(
                call.argumentsJson(), "tool call arguments must be a valid JSON object");
        if (!args.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "tool call arguments must be a valid JSON object");
        }
        functionCall.set("args", args);
      } else if (block instanceof ProviderOpaqueBlock opaqueBlock) {
        if (!"gemini_part".equals(opaqueBlock.blockType())) {
          return null;
        }
      } else {
        return null;
      }
      if (data.thought() != null && !(block instanceof ProviderTextBlock)) {
        part.put("thought", data.thought().booleanValue());
      }
      if (data.signature() != null) {
        part.put("thoughtSignature", data.signature());
      }
      parts.add(part);
      payloadParts.add(part.deepCopy());
    }

    if (!validateGeminiReplayPayload(payload, blocks)) {
      return null;
    }
    return parts;
  }

  private static ProviderBlockData providerBlockData(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      return textBlock.providerBlockData();
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      return thinkingBlock.providerBlockData();
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      return toolCallBlock.providerBlockData();
    }
    if (block instanceof ProviderOpaqueBlock opaqueBlock) {
      return opaqueBlock.providerBlockData();
    }
    if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
      return redactedBlock.providerBlockData();
    }
    return null;
  }

  private static void mergeNativeFields(ObjectNode target, JsonNode fields) {
    if (fields == null || !fields.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini assistant block native fields");
    }
    for (var field : fields.properties()) {
      target.set(field.getKey(), field.getValue().deepCopy());
    }
  }

  private static void encodeAssistantBlock(ArrayNode parts, ProviderContentBlock block) {
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      if (thinkingBlock.thinking() == null || thinkingBlock.thinking().isEmpty()) {
        return;
      }
      ObjectNode part = parts.addObject();
      part.put("text", thinkingBlock.thinking());
      return;
    }
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode part = parts.addObject();
      part.put("text", textBlock.text());
      return;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      // assistant JSON 诊断（如 tool_call_diagnostic）降级为普通文本 part。
      ObjectNode part = parts.addObject();
      part.put("text", jsonBlock.json());
      return;
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      ProviderToolCall call = toolCallBlock.toolCall();
      ObjectNode part = parts.addObject();
      ObjectNode functionCall = part.putObject("functionCall");
      functionCall.put("name", call.name());
      if (call.id() != null && !call.id().isBlank()) {
        functionCall.put("id", call.id());
      }
      JsonNode argsNode =
          ProviderJsonHelper.parseStrictJson(
              call.argumentsJson(), "tool call arguments must be a valid JSON object");
      if (!argsNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool call arguments must be a valid JSON object");
      }
      functionCall.set("args", argsNode);
      return;
    }
    if (block instanceof ProviderOpaqueBlock || block instanceof ProviderRedactedThinkingBlock) {
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported ASSISTANT content block");
  }

  /** thoughtSignature 属于已知字段：出现时必须是非空白字符串；作为 opaque 原生事实只在原位回放时使用。 */
  private static void validateReplayThoughtSignature(JsonNode part) {
    if (part.has("thoughtSignature")
        && (!part.get("thoughtSignature").isString()
            || part.get("thoughtSignature").asString("").isBlank())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }
  }

  /**
   * 完成结构校验、已知字段类型校验与 durable 一致性比对：结构/类型损坏一律 INVALID_REQUEST；返回 payload 的语义投影是否与 durable
   * 内容完全一致（不一致表示历史被改写，调用方应退回语义编码而非回放陈旧 payload）。
   */
  private static boolean validateGeminiReplayPayload(
      JsonNode payload, List<ProviderContentBlock> durableContents) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    // 严格检查顶级字段白名单：只能有 role 和 parts，禁止无关字段混入
    for (String f : payload.propertyNames()) {
      if (!"role".equals(f) && !"parts".equals(f)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }

    if (!payload.has("role")
        || !payload.get("role").isString()
        || !"model".equals(payload.get("role").asString(""))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    if (!payload.has("parts") || !payload.get("parts").isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    ArrayNode partsArray = (ArrayNode) payload.get("parts");

    StringBuilder payloadText = new StringBuilder();
    StringBuilder payloadThinking = new StringBuilder();
    record ReplayPayloadCall(String id, String name, String argsJson) {}
    List<ReplayPayloadCall> payloadCalls = new ArrayList<>();

    for (JsonNode item : partsArray) {
      if (!item.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }

      // 已知语义（text/thinking/functionCall）严格核对；未知 Part 与额外成员作为 opaque 原生事实原样保留。
      boolean hasText = item.has("text");
      boolean hasFunctionCall = item.has("functionCall");

      if (hasText && hasFunctionCall) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }

      validateReplayThoughtSignature(item);

      if (!hasText && !hasFunctionCall) {
        // inlineData/fileData/executableCode/codeExecutionResult/toolCall/toolResponse 等官方 Part 与未知
        // Part 都作为 opaque 原生事实：只在 affinity 匹配时原位回放。
        continue;
      }

      if (hasText) {
        if (!item.get("text").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (item.has("thought") && !item.get("thought").isBoolean()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }

        String txt = item.get("text").asString("");
        if (item.has("thought") && item.get("thought").asBoolean(false)) {
          payloadThinking.append(txt);
        } else {
          payloadText.append(txt);
        }
      } else { // hasFunctionCall
        JsonNode fn = item.get("functionCall");
        if (!fn.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }

        if (!fn.has("name") || !fn.get("name").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (!fn.has("args") || !fn.get("args").isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (fn.has("id") && !fn.get("id").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }

        String name = fn.get("name").asString("");
        String id = fn.has("id") ? fn.get("id").asString("") : null;
        String argsJson = fn.get("args").toString();
        payloadCalls.add(new ReplayPayloadCall(id, name, argsJson));
      }
    }

    // 提取 durable 内容
    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text() != null ? tb.text() : "");
      } else if (block instanceof ProviderThinkingBlock tb) {
        durableThinking.append(tb.thinking() != null ? tb.thinking() : "");
      } else if (block instanceof ProviderToolCallBlock tcb) {
        ProviderToolCall call = tcb.toolCall();
        JsonNode dNode =
            ProviderJsonHelper.parseStrictJson(
                call.argumentsJson(), "invalid Gemini replay payload");
        if (!dNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        durableCalls.add(call);
      } else if (block instanceof ProviderOpaqueBlock
          || block instanceof ProviderRedactedThinkingBlock) {
        // Native opaque and redacted content does not contribute to durable semantic comparison.
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }

    // durable 语义一致性：不一致返回 false（退回语义编码），结构/类型损坏已在上面抛出。
    if (!payloadText.toString().equals(durableText.toString())) {
      return false;
    }
    if (!payloadThinking.toString().equals(durableThinking.toString())) {
      return false;
    }
    if (payloadCalls.size() != durableCalls.size()) {
      return false;
    }
    for (int i = 0; i < payloadCalls.size(); i++) {
      ReplayPayloadCall pCall = payloadCalls.get(i);
      ProviderToolCall dCall = durableCalls.get(i);
      if (!Objects.equals(pCall.name(), dCall.name())) {
        return false;
      }
      String pId = (pCall.id() != null && !pCall.id().isBlank()) ? pCall.id() : null;
      String dId = (dCall.id() != null && !dCall.id().isBlank()) ? dCall.id() : null;
      if (!Objects.equals(pId, dId)) {
        return false;
      }
      JsonNode pArgs;
      JsonNode dArgs;
      try {
        // 数值按 scale 无关比对（1.0 与 1.00 同值）；比较专用 reader 只读取比较树，wire 仍用 replay 原生 args。
        pArgs = COMPARISON_READER.readTree(pCall.argsJson());
        dArgs = COMPARISON_READER.readTree(dCall.argumentsJson());
      } catch (Exception e) {
        // durable 参数本身无法解析属于请求事实损坏，必须明确拒绝而非静默 fallback。
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
      if (!Objects.equals(pArgs, dArgs)) {
        return false;
      }
    }
    return true;
  }
}
