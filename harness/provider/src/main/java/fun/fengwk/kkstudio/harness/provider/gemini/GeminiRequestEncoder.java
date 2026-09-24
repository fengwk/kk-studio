package fun.fengwk.kkstudio.harness.provider.gemini;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.RequestBodySizeGuard;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google AI Gemini GenerateContent 协议请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 Gemini streamGenerateContent 规范的 UTF-8 JSON 字节数组，并生成
 * sourcePrefixHash。
 */
final class GeminiRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  // data:[<mediatype>][;base64],<data>
  private static final Pattern DATA_URI_PATTERN =
      Pattern.compile("^data:([^;,]+)(?:;base64)?,(.*)$", Pattern.CASE_INSENSITIVE);

  // data:<mediatype>;base64,<data>：工具结果只接受内联 Base64 媒体
  private static final Pattern BASE64_DATA_URI_PATTERN =
      Pattern.compile("^data:([^;,]+);base64,(.*)$", Pattern.CASE_INSENSITIVE);

  /** 工具结果可内联的图片 MIME：只保留官方明确列举的保守集合，不扩大到全部 image/*。 */
  private static final Set<String> TOOL_RESULT_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");

  /** 工具结果唯一可内联的文档 MIME。 */
  private static final String TOOL_RESULT_DOCUMENT_TYPE = "application/pdf";

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

    ObjectNode root = NODES.objectNode();

    // 1. 请求唯一的系统指令映射为顶层 systemInstruction.parts
    ObjectNode systemInstruction = root.putObject("systemInstruction");
    ArrayNode sysParts = systemInstruction.putArray("parts");
    ObjectNode systemPart = sysParts.addObject();
    systemPart.put("text", request.systemInstruction());

    // 2. generationConfig 映射
    encodeGenerationConfig(root, request);

    // 3. tools 映射
    ArrayNode toolsArray = encodeTools(root, request.tools());

    // 4. contents 映射与 prefix hash 维护（严格按当前实际构建的、已合并相邻同 role 的 wire contents 计算）
    ArrayNode contentsArray = root.putArray("contents");

    String currentRole = null;
    ArrayNode currentParts = null;

    for (int i = 0; i < request.messages().size(); i++) {
      ProviderMessage msg = request.messages().get(i);

      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        String wireRole = "model";
        // 每个历史 assistant 的比较点是加入该 assistant 前，按真实 wire contents 计算
        String currentPrefixHash =
            GeminiPrefixHasher.calculateHash(systemInstruction, toolsArray, contentsArray);

        if (currentParts == null || !wireRole.equals(currentRole)) {
          ObjectNode contentNode = contentsArray.addObject();
          contentNode.put("role", wireRole);
          currentRole = wireRole;
          currentParts = contentNode.putArray("parts");
        }

        if (canReplay(
            msg.replayState(),
            descriptor,
            request.model().modelId(),
            currentPrefixHash,
            msg.contents())) {
          for (JsonNode partNode : msg.replayState().payload().path("parts")) {
            currentParts.add(partNode.deepCopy());
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
            encodeUserBlock(currentParts, block);
          }
        } else { // TOOL
          for (ProviderContentBlock block : msg.contents()) {
            if (block instanceof ProviderToolResultBlock resultBlock) {
              encodeToolResultBlock(currentParts, resultBlock);
            }
          }
        }
      }
    }

    if (contentsArray.size() == 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "Gemini contents must contain at least one message");
    }

    String finalPrefixHash =
        GeminiPrefixHasher.calculateHash(systemInstruction, toolsArray, contentsArray);

    try {
      byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(root);
      bodySizeGuard.enforce(bytes);
      return new GeminiEncodedRequest(bytes, finalPrefixHash);
    } catch (JsonProcessingException e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize Gemini request body JSON");
    }
  }

  private static void validateCacheControl(ProviderCacheControl cacheControl) {
    if (cacheControl != null
        && (cacheControl.retention() != PromptCacheRetention.NONE
            || !cacheControl.breakpoints().isEmpty())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini only supports automatic prompt caching; explicit cacheControl is not allowed");
    }
  }

  private static void encodeGenerationConfig(ObjectNode root, ProviderRequest request) {
    ModelVariant variant = request.variant();
    ObjectNode genConfig = NODES.objectNode();

    // 输出预算只来自 Model 级 limit.output（普通请求再按剩余上下文收敛），不来自 variant。
    genConfig.put("maxOutputTokens", request.outputTokens());

    // reasoning effort 映射到 thinkingConfig：未声明时不生成，off 显式关闭，其余级别下发生效级别
    String reasoningEffort = variant != null ? variant.reasoningEffort() : null;
    if (reasoningEffort != null) {
      ObjectNode thinkingConfig = genConfig.putObject("thinkingConfig");
      if (variant.reasoningOff()) {
        thinkingConfig.put("includeThoughts", false);
        thinkingConfig.put("thinkingBudget", 0);
      } else {
        thinkingConfig.put("includeThoughts", true);
        thinkingConfig.put("thinkingLevel", reasoningEffort);
      }
    }

    if (genConfig.size() > 0) {
      root.set("generationConfig", genConfig);
    }
  }

  private static ArrayNode encodeTools(ObjectNode root, List<ProviderToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    ArrayNode toolsArray = root.putArray("tools");
    ObjectNode toolObj = toolsArray.addObject();
    ArrayNode fnDecls = toolObj.putArray("functionDeclarations");

    for (ProviderToolDefinition tool : tools) {
      ObjectNode fn = fnDecls.addObject();
      fn.put("name", tool.name());
      if (tool.description() != null && !tool.description().isBlank()) {
        fn.put("description", tool.description());
      }
      JsonNode schemaNode =
          parseStrictJson(tool.inputSchemaJson(), "tool inputSchemaJson is not valid JSON");
      if (!schemaNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool parameters must be a JSON object");
      }
      fn.set("parameters", schemaNode);
    }
    return toolsArray;
  }

  private static void encodeUserBlock(ArrayNode parts, ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode part = parts.addObject();
      part.put("text", textBlock.text());
      return;
    }
    if (block instanceof ProviderImageBlock imageBlock) {
      encodeMediaBlock(parts, imageBlock.mediaType(), imageBlock.source());
      return;
    }
    if (block instanceof ProviderAudioBlock audioBlock) {
      encodeMediaBlock(parts, audioBlock.mediaType(), audioBlock.source());
      return;
    }
    if (block instanceof ProviderVideoBlock videoBlock) {
      encodeMediaBlock(parts, videoBlock.mediaType(), videoBlock.source());
      return;
    }
    if (block instanceof ProviderDocumentBlock documentBlock) {
      encodeMediaBlock(parts, documentBlock.mediaType(), documentBlock.source());
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported content block in user message");
  }

  private static void encodeMediaBlock(ArrayNode parts, String mediaType, String source) {
    if (source == null || source.isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source URI must not be blank");
    }
    if (source.startsWith("data:")) {
      Matcher matcher = DATA_URI_PATTERN.matcher(source);
      if (matcher.matches()) {
        String dataMime = matcher.group(1);
        String base64Data = matcher.group(2);
        String finalMime = (dataMime != null && !dataMime.isBlank()) ? dataMime : mediaType;
        ObjectNode part = parts.addObject();
        ObjectNode inlineData = part.putObject("inlineData");
        inlineData.put("mimeType", finalMime);
        inlineData.put("data", base64Data);
        return;
      }
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid data URI in media source");
    }

    try {
      URI uri = URI.create(source);
      String scheme = uri.getScheme();
      if (scheme == null
          || (!scheme.equalsIgnoreCase("http")
              && !scheme.equalsIgnoreCase("https")
              && !scheme.equalsIgnoreCase("gs"))) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "media fileUri scheme must be http, https or gs");
      }
      ObjectNode part = parts.addObject();
      ObjectNode fileData = part.putObject("fileData");
      fileData.put("mimeType", mediaType);
      fileData.put("fileUri", source);
    } catch (IllegalArgumentException ex) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid media source URI");
    }
  }

  /**
   * 工具结果编码：文本/JSON 结果进入 {@code functionResponse.response}，媒体必须内联在 {@code
   * functionResponse.parts[].inlineData} 内。
   *
   * <p>media 绝不能作为外层 {@code Content.parts} 的同级 part：那样媒体会变成该 role 的独立输入，而不是这次函数调用的返回值 （见
   * https://ai.google.dev/api/generate-content#v1beta.FunctionResponse 与
   * https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling#mm-fr）。
   *
   * <p>Gemini Developer API（generativelanguage v1beta）的 {@code FunctionResponseBlob} 只声明 {@code
   * mimeType} 与 {@code data}，没有 {@code displayName}，因此 {@code response} 内 {@code {"$ref":
   * "<displayName>"}} 的引用机制在 本协议下不可用：编码器只把媒体挂到 {@code functionResponse.parts} 上，绝不伪造 {@code
   * displayName} 或 {@code $ref}。
   */
  private static void encodeToolResultBlock(ArrayNode parts, ProviderToolResultBlock resultBlock) {
    List<ProviderContentBlock> textAndJsonBlocks = new ArrayList<>();
    List<ProviderContentBlock> mediaBlocks = new ArrayList<>();

    for (ProviderContentBlock block : resultBlock.contents()) {
      if (block instanceof ProviderTextBlock || block instanceof ProviderJsonBlock) {
        textAndJsonBlocks.add(block);
      } else if (block instanceof ProviderImageBlock
          || block instanceof ProviderAudioBlock
          || block instanceof ProviderVideoBlock
          || block instanceof ProviderDocumentBlock) {
        // 媒体统一走保守白名单校验：音频/视频与非法来源在 encodeToolResultMediaPart 中明确拒绝
        mediaBlocks.add(block);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unsupported content block in tool result");
      }
    }

    JsonNode responseObj = encodeToolResultResponse(textAndJsonBlocks, resultBlock.error());

    ObjectNode part = parts.addObject();
    ObjectNode functionResponse = part.putObject("functionResponse");
    functionResponse.put("name", resultBlock.toolName());
    functionResponse.put("id", resultBlock.toolCallId());
    functionResponse.set("response", responseObj);

    if (!mediaBlocks.isEmpty()) {
      ArrayNode responseParts = functionResponse.putArray("parts");
      for (ProviderContentBlock mediaBlock : mediaBlocks) {
        responseParts.add(encodeToolResultMediaPart(mediaBlock));
      }
    }
  }

  /**
   * 工具结果媒体按保守白名单内联为 {@code FunctionResponsePart.inlineData}：只接受 image/jpeg、image/png、image/webp 与
   * application/pdf 的 Base64 data URI。
   *
   * <p>音频、视频、其他 MIME 与非 data URI 来源（含 http(s)/gs URL：v1beta 的 {@code FunctionResponsePart} 只有
   * {@code inlineData}）都明确以 {@code INVALID_REQUEST} 失败，绝不降级为看似成功却丢弃媒体的请求。
   */
  private static ObjectNode encodeToolResultMediaPart(ProviderContentBlock block) {
    String declaredMediaType;
    String source;
    if (block instanceof ProviderImageBlock imageBlock) {
      declaredMediaType = imageBlock.mediaType();
      source = imageBlock.source();
    } else if (block instanceof ProviderDocumentBlock documentBlock) {
      declaredMediaType = documentBlock.mediaType();
      source = documentBlock.source();
    } else {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Gemini tool result only supports IMAGE and DOCUMENT media blocks");
    }

    String mediaType = declaredMediaType == null ? "" : declaredMediaType.toLowerCase(Locale.ROOT);
    if (!TOOL_RESULT_IMAGE_TYPES.contains(mediaType)
        && !TOOL_RESULT_DOCUMENT_TYPE.equals(mediaType)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported tool result media type: " + mediaType);
    }

    ObjectNode part = NODES.objectNode();
    ObjectNode inlineData = part.putObject("inlineData");
    inlineData.put("mimeType", mediaType);
    inlineData.put("data", extractToolResultBase64(source, mediaType));
    return part;
  }

  /** 工具结果媒体来源必须是 MIME 与声明一致的 Base64 data URI；任何其他形态都 fail closed。 */
  private static String extractToolResultBase64(String source, String mediaType) {
    Matcher matcher = source == null ? null : BASE64_DATA_URI_PATTERN.matcher(source);
    if (matcher != null && matcher.matches()) {
      String dataUriMediaType = matcher.group(1);
      String base64Data = matcher.group(2);
      if (dataUriMediaType != null
          && dataUriMediaType.equalsIgnoreCase(mediaType)
          && base64Data != null
          && !base64Data.isEmpty()) {
        return base64Data;
      }
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "tool result media source must be a base64 data URI matching mediaType");
  }

  private static JsonNode encodeToolResultResponse(
      List<ProviderContentBlock> textAndJsonBlocks, boolean isError) {
    int size = textAndJsonBlocks.size();

    if (!isError) {
      if (size == 0) {
        // 空文本使用 response.result
        return NODES.objectNode().put("result", "");
      } else if (size == 1) {
        ProviderContentBlock single = textAndJsonBlocks.get(0);
        if (single instanceof ProviderJsonBlock jb) {
          JsonNode parsed = parseStrictJson(jb.json(), "invalid JSON block in tool result");
          if (parsed.isObject()) {
            // 单 JSON object 可直接作为 response object
            return parsed.deepCopy();
          } else {
            // 其他单 JSON 放在 result
            return NODES.objectNode().set("result", parsed);
          }
        } else {
          ProviderTextBlock tb = (ProviderTextBlock) single;
          // 单文本使用 response.result
          return NODES.objectNode().put("result", tb.text() != null ? tb.text() : "");
        }
      } else {
        // 多个 text/JSON 使用有序 results 数组
        ArrayNode results = NODES.arrayNode();
        for (ProviderContentBlock b : textAndJsonBlocks) {
          if (b instanceof ProviderTextBlock tb) {
            results.add(tb.text() != null ? tb.text() : "");
          } else if (b instanceof ProviderJsonBlock jb) {
            results.add(parseStrictJson(jb.json(), "invalid JSON block in tool result"));
          }
        }
        return NODES.objectNode().set("results", results);
      }
    } else {
      // error=true 时用明确的 error wrapper 且不覆盖原始 JSON
      ObjectNode wrapper = NODES.objectNode();
      wrapper.put("error", true);
      if (size == 0) {
        wrapper.put("result", "");
      } else if (size == 1) {
        ProviderContentBlock single = textAndJsonBlocks.get(0);
        if (single instanceof ProviderJsonBlock jb) {
          JsonNode parsed = parseStrictJson(jb.json(), "invalid JSON block in tool result");
          wrapper.set("result", parsed);
        } else {
          ProviderTextBlock tb = (ProviderTextBlock) single;
          wrapper.put("result", tb.text() != null ? tb.text() : "");
        }
      } else {
        ArrayNode results = NODES.arrayNode();
        for (ProviderContentBlock b : textAndJsonBlocks) {
          if (b instanceof ProviderTextBlock tb) {
            results.add(tb.text() != null ? tb.text() : "");
          } else if (b instanceof ProviderJsonBlock jb) {
            results.add(parseStrictJson(jb.json(), "invalid JSON block in tool result"));
          }
        }
        wrapper.set("results", results);
      }
      return wrapper;
    }
  }

  private static void encodeAssistantBlock(ArrayNode parts, ProviderContentBlock block) {
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      ObjectNode part = parts.addObject();
      part.put("text", thinkingBlock.thinking());
      part.put("thought", true);
      return;
    }
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode part = parts.addObject();
      part.put("text", textBlock.text());
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
          parseStrictJson(call.argumentsJson(), "tool call arguments must be a valid JSON object");
      if (!argsNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool call arguments must be a valid JSON object");
      }
      functionCall.set("args", argsNode);
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported ASSISTANT content block");
  }

  private static boolean canReplay(
      ProviderReplayState replayState,
      ProviderDescriptor descriptor,
      String requestedModel,
      String currentPrefixHash,
      List<ProviderContentBlock> durableContents) {
    if (replayState == null) {
      return false;
    }
    if (replayState.format() != ProviderReplayFormat.GEMINI_CONTENT) {
      return false;
    }

    // 同 format (GEMINI_CONTENT) payload 无论 affinity/hash 是否匹配都先严格校验！
    // 损坏必须 INVALID_REQUEST！
    validateGeminiReplayPayload(replayState.payload(), durableContents);

    // 校验通过后再按 affinity/hash 决定 replay/fallback
    if (!replayState.affinity().equals(descriptor.affinity(requestedModel))) {
      return false;
    }
    if (!replayState.sourcePrefixHash().equals(currentPrefixHash)) {
      return false;
    }
    return true;
  }

  private static void validateGeminiReplayPayload(
      JsonNode payload, List<ProviderContentBlock> durableContents) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    // 严格检查顶级字段白名单：只能有 role 和 parts，禁止无关字段混入
    Iterator<String> topFields = payload.fieldNames();
    while (topFields.hasNext()) {
      String f = topFields.next();
      if (!"role".equals(f) && !"parts".equals(f)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }

    if (!payload.has("role")
        || !payload.get("role").isTextual()
        || !"model".equals(payload.get("role").asText())) {
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

      // 白名单 shape 检查：必须且只能是 Text/Thinking part 或 FunctionCall part
      // 不允许无关字段混入，保留 thoughtSignature 原位
      boolean hasText = item.has("text");
      boolean hasFunctionCall = item.has("functionCall");

      if (hasText && hasFunctionCall) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
      if (!hasText && !hasFunctionCall) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }

      if (hasText) {
        if (!item.get("text").isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        Iterator<String> it = item.fieldNames();
        while (it.hasNext()) {
          String fn = it.next();
          if ("text".equals(fn)) {
            continue;
          }
          if ("thought".equals(fn)) {
            if (!item.get(fn).isBoolean()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
            }
          } else if ("thoughtSignature".equals(fn)) {
            if (!item.get(fn).isTextual() || item.get(fn).asText().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
            }
          } else {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
          }
        }

        String txt = item.get("text").asText();
        if (item.has("thought") && item.get("thought").asBoolean()) {
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
        Iterator<String> it = item.fieldNames();
        while (it.hasNext()) {
          String fnName = it.next();
          if ("functionCall".equals(fnName)) {
            continue;
          }
          if ("thoughtSignature".equals(fnName)) {
            if (!item.get(fnName).isTextual() || item.get(fnName).asText().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
            }
          } else {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
          }
        }

        if (!fn.has("name") || !fn.get("name").isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (!fn.has("args") || !fn.get("args").isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        Iterator<String> fnFields = fn.fieldNames();
        while (fnFields.hasNext()) {
          String fnField = fnFields.next();
          if ("name".equals(fnField)) {
            continue;
          }
          if ("id".equals(fnField)) {
            if (!fn.get("id").isTextual()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
            }
          } else if ("args".equals(fnField)) {
            if (!fn.get("args").isObject()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
            }
          } else {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
          }
        }

        String name = fn.get("name").asText();
        String id = fn.has("id") ? fn.get("id").asText() : null;
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
        JsonNode dNode = parseStrictJson(call.argumentsJson(), "invalid Gemini replay payload");
        if (!dNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        durableCalls.add(call);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }

    // 比对 text 与 thinking
    if (!payloadText.toString().equals(durableText.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }
    if (!payloadThinking.toString().equals(durableThinking.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    // 比对 tool calls 数量、id、name、arguments 全一致
    if (payloadCalls.size() != durableCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }
    for (int i = 0; i < payloadCalls.size(); i++) {
      ReplayPayloadCall pCall = payloadCalls.get(i);
      ProviderToolCall dCall = durableCalls.get(i);
      if (!Objects.equals(pCall.name(), dCall.name())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
      String pId = (pCall.id() != null && !pCall.id().isBlank()) ? pCall.id() : null;
      String dId = (dCall.id() != null && !dCall.id().isBlank()) ? dCall.id() : null;
      if (!Objects.equals(pId, dId)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
      try {
        JsonNode pArgs = OBJECT_MAPPER.readTree(pCall.argsJson());
        JsonNode dArgs = OBJECT_MAPPER.readTree(dCall.argumentsJson());
        if (!Objects.equals(pArgs, dArgs)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
      } catch (Exception e) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }
  }

  private static JsonNode parseStrictJson(String json, String fixedErrorMessage) {
    if (json == null || json.isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, fixedErrorMessage);
    }
    try (JsonParser parser = OBJECT_MAPPER.createParser(json)) {
      JsonNode node = OBJECT_MAPPER.readTree(parser);
      if (node == null) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, fixedErrorMessage);
      }
      if (parser.nextToken() != null) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, fixedErrorMessage);
      }
      return node;
    } catch (Exception ex) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, fixedErrorMessage);
    }
  }
}
