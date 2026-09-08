package fun.fengwk.kkstudio.harness.provider.gemini;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

  GeminiEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    validateCacheControl(request.cacheControl());

    ObjectNode root = NODES.objectNode();

    // 1. leading SYSTEM 合并到 systemInstruction.parts（文本限定）
    List<ProviderMessage> messages = request.messages();
    List<ProviderContentBlock> leadingSystemBlocks = new ArrayList<>();
    List<ProviderMessage> conversationMessages = new ArrayList<>();
    boolean seenNonSystem = false;

    for (ProviderMessage msg : messages) {
      if (msg.role() == ProviderMessageRole.SYSTEM) {
        if (seenNonSystem) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "Gemini does not allow mid-conversation SYSTEM messages");
        }
        leadingSystemBlocks.addAll(msg.contents());
      } else {
        seenNonSystem = true;
        conversationMessages.add(msg);
      }
    }

    ObjectNode systemInstruction = null;
    if (!leadingSystemBlocks.isEmpty()) {
      systemInstruction = root.putObject("systemInstruction");
      ArrayNode sysParts = systemInstruction.putArray("parts");
      for (ProviderContentBlock block : leadingSystemBlocks) {
        if (block instanceof ProviderTextBlock textBlock) {
          ObjectNode part = sysParts.addObject();
          part.put("text", textBlock.text());
        } else {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "systemInstruction parts must be text; unsupported content block: "
                  + block.getClass().getSimpleName());
        }
      }
    }

    // 2. generationConfig 映射
    encodeGenerationConfig(root, request);

    // 3. tools 映射
    ArrayNode toolsArray = encodeTools(root, request.tools());

    // 4. contents 映射与 prefix hash 维护
    ArrayNode contentsArray = root.putArray("contents");
    ArrayNode prefixContents = NODES.arrayNode();

    String currentRole = null;
    ArrayNode currentParts = null;

    for (int i = 0; i < conversationMessages.size(); i++) {
      ProviderMessage msg = conversationMessages.get(i);
      String currentPrefixHash =
          GeminiPrefixHasher.calculateHash(systemInstruction, toolsArray, prefixContents);

      String wireRole;
      if (msg.role() == ProviderMessageRole.USER || msg.role() == ProviderMessageRole.TOOL) {
        wireRole = "user";
      } else if (msg.role() == ProviderMessageRole.ASSISTANT) {
        wireRole = "model";
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unsupported message role: " + msg.role());
      }

      if (currentParts == null || !wireRole.equals(currentRole)) {
        ObjectNode contentNode = contentsArray.addObject();
        contentNode.put("role", wireRole);
        currentRole = wireRole;
        currentParts = contentNode.putArray("parts");
      }

      ObjectNode prefixMsgNode = NODES.objectNode();
      prefixMsgNode.put("role", wireRole);
      ArrayNode prefixMsgParts = prefixMsgNode.putArray("parts");

      if (msg.role() == ProviderMessageRole.USER) {
        for (ProviderContentBlock block : msg.contents()) {
          encodeUserBlock(currentParts, block);
          encodeUserBlock(prefixMsgParts, block);
        }
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        for (ProviderContentBlock block : msg.contents()) {
          if (block instanceof ProviderToolResultBlock resultBlock) {
            encodeToolResultBlock(currentParts, resultBlock);
            encodeToolResultBlock(prefixMsgParts, resultBlock);
          } else {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "TOOL message must only contain ProviderToolResultBlock");
          }
        }
      } else { // ASSISTANT (model)
        if (canReplay(
            msg.replayState(),
            descriptor,
            request.model().modelName(),
            currentPrefixHash,
            msg.contents())) {
          for (JsonNode partNode : msg.replayState().payload().path("parts")) {
            currentParts.add(partNode.deepCopy());
            prefixMsgParts.add(partNode.deepCopy());
          }
        } else {
          for (ProviderContentBlock block : msg.contents()) {
            encodeAssistantBlock(currentParts, block);
            encodeAssistantBlock(prefixMsgParts, block);
          }
        }
      }

      prefixContents.add(prefixMsgNode);
    }

    if (contentsArray.size() == 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "Gemini contents must contain at least one message");
    }

    String finalPrefixHash =
        GeminiPrefixHasher.calculateHash(systemInstruction, toolsArray, prefixContents);

    try {
      byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(root);
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

    if (variant != null) {
      if (variant.maxOutputTokens() != null) {
        genConfig.put("maxOutputTokens", variant.maxOutputTokens());
      }
      if (variant.temperature() != null) {
        genConfig.put("temperature", variant.temperature());
      }
      if (variant.topP() != null) {
        genConfig.put("topP", variant.topP());
      }
      if (variant.topK() != null) {
        genConfig.put("topK", variant.topK());
      }
      if (variant.stopSequences() != null && !variant.stopSequences().isEmpty()) {
        ArrayNode stopSeq = genConfig.putArray("stopSequences");
        for (String seq : variant.stopSequences()) {
          stopSeq.add(seq);
        }
      }
      if (variant.presencePenalty() != null) {
        genConfig.put("presencePenalty", variant.presencePenalty());
      }
      if (variant.frequencyPenalty() != null) {
        genConfig.put("frequencyPenalty", variant.frequencyPenalty());
      }
    }

    // reasoning effort 映射到 thinkingConfig
    boolean modelReasoning = request.model().reasoning();
    String reasoningEffort = variant != null ? variant.reasoningEffort() : null;

    if (modelReasoning || reasoningEffort != null) {
      ObjectNode thinkingConfig = genConfig.putObject("thinkingConfig");
      thinkingConfig.put("includeThoughts", true);

      if (reasoningEffort != null) {
        String effort = reasoningEffort.trim().toLowerCase(Locale.ROOT);
        if ("minimal".equals(effort)
            || "low".equals(effort)
            || "medium".equals(effort)
            || "high".equals(effort)) {
          thinkingConfig.put("thinkingLevel", effort);
        } else {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "unknown or unsupported reasoning effort: " + reasoningEffort);
        }
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
      JsonNode schemaNode;
      try {
        schemaNode = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
      } catch (JsonProcessingException exception) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool inputSchemaJson is not valid JSON");
      }
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
        ProviderErrorKind.INVALID_REQUEST,
        "unsupported content block for Gemini request: " + block.getClass().getSimpleName());
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
            ProviderErrorKind.INVALID_REQUEST,
            "media fileUri scheme must be http, https or gs: " + source);
      }
      ObjectNode part = parts.addObject();
      ObjectNode fileData = part.putObject("fileData");
      fileData.put("mimeType", mediaType);
      fileData.put("fileUri", source);
    } catch (IllegalArgumentException ex) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid media source URI: " + source);
    }
  }

  private static void encodeToolResultBlock(ArrayNode parts, ProviderToolResultBlock resultBlock) {
    ObjectNode part = parts.addObject();
    ObjectNode functionResponse = part.putObject("functionResponse");
    functionResponse.put("name", resultBlock.toolName());
    if (resultBlock.toolCallId() != null && !resultBlock.toolCallId().isBlank()) {
      functionResponse.put("id", resultBlock.toolCallId());
    }

    ObjectNode responseObj = functionResponse.putObject("response");
    StringBuilder textBuilder = new StringBuilder();
    for (ProviderContentBlock b : resultBlock.contents()) {
      if (b instanceof ProviderTextBlock tb) {
        textBuilder.append(tb.text());
      }
    }
    responseObj.put("response", textBuilder.toString());
    responseObj.put("output", textBuilder.toString());
    if (resultBlock.error()) {
      responseObj.put("error", true);
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
      try {
        JsonNode argsNode = OBJECT_MAPPER.readTree(call.argumentsJson());
        if (argsNode.isObject()) {
          functionCall.set("args", argsNode);
        } else {
          functionCall.putObject("args");
        }
      } catch (JsonProcessingException e) {
        functionCall.putObject("args");
      }
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "unsupported ASSISTANT content block: " + block.getClass().getSimpleName());
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
    if (!replayState.affinity().equals(descriptor.affinity(requestedModel))) {
      return false;
    }
    if (!replayState.sourcePrefixHash().equals(currentPrefixHash)) {
      return false;
    }
    JsonNode payload = replayState.payload();
    if (!payload.isObject() || !payload.has("parts") || !payload.get("parts").isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload: missing parts array");
    }
    ArrayNode partsArray = (ArrayNode) payload.get("parts");
    boolean valid = validatePayloadAgainstDurable(partsArray, durableContents);
    if (!valid) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid Gemini replay payload: content does not match durable contents");
    }
    return true;
  }

  private static boolean validatePayloadAgainstDurable(
      ArrayNode partsArray, List<ProviderContentBlock> durableContents) {
    StringBuilder payloadText = new StringBuilder();
    StringBuilder payloadThinking = new StringBuilder();
    List<ProviderToolCall> payloadCalls = new ArrayList<>();

    for (JsonNode item : partsArray) {
      if (!item.isObject()) {
        return false;
      }
      boolean hasRecognizedField = false;

      if (item.has("text") && item.get("text").isTextual()) {
        hasRecognizedField = true;
        if (item.has("thought") && item.get("thought").asBoolean(false)) {
          payloadThinking.append(item.get("text").asText());
        } else {
          payloadText.append(item.get("text").asText());
        }
      }

      if (item.has("functionCall") && item.get("functionCall").isObject()) {
        hasRecognizedField = true;
        JsonNode fn = item.get("functionCall");
        if (!fn.has("name") || !fn.get("name").isTextual()) {
          return false;
        }
        String name = fn.get("name").asText();
        String id = (fn.has("id") && fn.get("id").isTextual()) ? fn.get("id").asText() : name;
        String args = fn.has("args") ? fn.get("args").toString() : "{}";
        payloadCalls.add(new ProviderToolCall(id, name, args));
      }

      if (!hasRecognizedField) {
        return false;
      }
    }

    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock tb) {
        durableThinking.append(tb.thinking());
      } else if (block instanceof ProviderToolCallBlock tcb) {
        durableCalls.add(tcb.toolCall());
      }
    }

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
      ProviderToolCall pCall = payloadCalls.get(i);
      ProviderToolCall dCall = durableCalls.get(i);
      if (!pCall.name().equals(dCall.name())) {
        return false;
      }
    }

    return true;
  }
}
