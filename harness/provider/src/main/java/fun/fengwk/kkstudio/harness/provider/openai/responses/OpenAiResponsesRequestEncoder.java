package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Responses 协议的请求编码器。
 *
 * <p>负责将 {@link ProviderRequest} 规范化编码为 OpenAI {@code /responses} 端点的 UTF-8 JSON 请求体， 并计算冻结的
 * sourcePrefixHash。
 */
final class OpenAiResponsesRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ALLOWED_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final String ALLOWED_DOCUMENT_TYPE = "application/pdf";
  private static final Set<String> ALLOWED_REPLAY_ITEM_TYPES =
      Set.of("message", "reasoning", "function_call");

  OpenAiResponsesEncodedRequest encode(
      ProviderRequest request, ProviderDescriptor descriptor, OpenAiResponsesConfig config) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(config, "config");

    validatePenalties(request.variant());

    ObjectNode root = NODES.objectNode();
    root.put("model", request.model().modelName());
    root.put("stream", true);
    root.put("store", false);

    applySamplingParameters(root, request.variant());
    applyReasoningParameters(root, request.variant());

    ArrayNode toolsArray = encodeTools(request.tools());
    if (toolsArray != null && !toolsArray.isEmpty()) {
      root.set("tools", toolsArray);
    }

    ArrayNode inputItems = NODES.arrayNode();
    List<ObjectNode> systemContentBlocks = new ArrayList<>();
    List<ObjectNode> conversationContentBlocks = new ArrayList<>();

    for (ProviderMessage msg : request.messages()) {
      encodeMessage(
          msg,
          descriptor,
          request.model().modelName(),
          toolsArray,
          inputItems,
          systemContentBlocks,
          conversationContentBlocks);
    }
    root.set("input", inputItems);

    // 计算冻结前缀哈希（在打 cache breakpoint 之前计算）
    String sourcePrefixHash = OpenAiResponsesPrefixHasher.calculateHash(toolsArray, inputItems);

    applyCacheControl(
        root,
        config.openAiPromptCacheMode(),
        request.cacheControl(),
        systemContentBlocks,
        conversationContentBlocks);

    byte[] utf8Bytes;
    try {
      utf8Bytes = OBJECT_MAPPER.writeValueAsBytes(root);
    } catch (JsonProcessingException e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize OpenAI Responses request JSON");
    }

    return new OpenAiResponsesEncodedRequest(utf8Bytes, sourcePrefixHash);
  }

  private static void validatePenalties(ModelVariant variant) {
    if (variant == null) {
      return;
    }
    if (variant.frequencyPenalty() != null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "frequencyPenalty parameter is not supported by OpenAI Responses API");
    }
    if (variant.presencePenalty() != null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "presencePenalty parameter is not supported by OpenAI Responses API");
    }
    if (variant.stopSequences() != null && !variant.stopSequences().isEmpty()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "stopSequences parameter is not supported by OpenAI Responses API");
    }
    if (variant.topK() != null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "topK parameter is not supported by OpenAI Responses API");
    }
  }

  private static void applySamplingParameters(ObjectNode root, ModelVariant variant) {
    if (variant == null) {
      return;
    }
    if (variant.temperature() != null) {
      root.put("temperature", variant.temperature());
    }
    if (variant.topP() != null) {
      root.put("top_p", variant.topP());
    }
    if (variant.maxOutputTokens() != null) {
      root.put("max_output_tokens", variant.maxOutputTokens());
    }
  }

  private static void applyReasoningParameters(ObjectNode root, ModelVariant variant) {
    if (variant == null || variant.reasoningEffort() == null) {
      return;
    }
    ObjectNode reasoning = root.putObject("reasoning");
    reasoning.put("effort", variant.reasoningEffort());
    reasoning.put("summary", "auto");
  }

  private static ArrayNode encodeTools(List<ProviderToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    ArrayNode array = NODES.arrayNode();
    for (ProviderToolDefinition tool : tools) {
      ObjectNode node = array.addObject();
      node.put("type", "function");
      node.put("name", tool.name());
      if (tool.description() != null && !tool.description().isBlank()) {
        node.put("description", tool.description());
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
      node.set("parameters", schemaNode);
      node.put("strict", false);
    }
    return array;
  }

  private static void encodeMessage(
      ProviderMessage msg,
      ProviderDescriptor descriptor,
      String requestedModel,
      ArrayNode toolsArray,
      ArrayNode inputItems,
      List<ObjectNode> systemContentBlocks,
      List<ObjectNode> conversationContentBlocks) {
    Objects.requireNonNull(msg, "msg");
    ProviderMessageRole role = msg.role();

    switch (role) {
      case SYSTEM -> {
        ObjectNode sysMsg = inputItems.addObject();
        sysMsg.put("type", "message");
        sysMsg.put("role", "system");
        ArrayNode contents = sysMsg.putArray("content");
        for (ProviderContentBlock block : msg.contents()) {
          ObjectNode blockNode = encodeSystemContentBlock(block);
          contents.add(blockNode);
          systemContentBlocks.add(blockNode);
        }
      }
      case USER -> {
        ObjectNode userMsg = inputItems.addObject();
        userMsg.put("type", "message");
        userMsg.put("role", "user");
        ArrayNode contents = userMsg.putArray("content");
        for (ProviderContentBlock block : msg.contents()) {
          ObjectNode blockNode = encodeUserContentBlock(block);
          contents.add(blockNode);
          conversationContentBlocks.add(blockNode);
        }
      }
      case ASSISTANT -> {
        ProviderReplayState replayState = msg.replayState();
        if (replayState != null) {
          String currentPrefixHash =
              OpenAiResponsesPrefixHasher.calculateHash(toolsArray, inputItems);
          if (canReplay(
              replayState, descriptor, requestedModel, currentPrefixHash, msg.contents())) {
            ArrayNode outputArray = extractOutputArray(replayState.payload());
            for (JsonNode item : outputArray) {
              inputItems.add(item.deepCopy());
            }
            return;
          }
        }

        // Semantic fallback
        encodeAssistantSemanticFallback(msg.contents(), inputItems, conversationContentBlocks);
      }
      case TOOL -> {
        for (ProviderContentBlock block : msg.contents()) {
          if (block instanceof ProviderToolResultBlock resultBlock) {
            ObjectNode toolOutput = inputItems.addObject();
            toolOutput.put("type", "function_call_output");
            toolOutput.put("call_id", resultBlock.toolCallId());
            encodeToolResultContent(resultBlock, toolOutput);
          } else {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "TOOL message must only contain ProviderToolResultBlock");
          }
        }
      }
    }
  }

  private static ObjectNode encodeSystemContentBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_text");
      node.put("text", textBlock.text());
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "OpenAI Responses does not support content block type in SYSTEM: "
            + block.getClass().getSimpleName());
  }

  private static ObjectNode encodeUserContentBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderImageBlock imageBlock) {
      validateImageType(imageBlock.mediaType());
      validateUri(imageBlock.source());
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_image");
      node.put("image_url", imageBlock.source());
      node.put("detail", "auto");
      return node;
    }
    if (block instanceof ProviderDocumentBlock docBlock) {
      validateDocumentType(docBlock.mediaType());
      validateUri(docBlock.source());
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_file");
      if (docBlock.source().startsWith("data:")) {
        node.put("file_data", docBlock.source());
        node.put("filename", "document.pdf");
      } else {
        node.put("file_url", docBlock.source());
      }
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "OpenAI Responses does not support content block type: "
            + block.getClass().getSimpleName());
  }

  private static void encodeAssistantSemanticFallback(
      List<ProviderContentBlock> contents,
      ArrayNode inputItems,
      List<ObjectNode> conversationContentBlocks) {
    StringBuilder textBuf = new StringBuilder();
    StringBuilder thinkBuf = new StringBuilder();
    List<ProviderToolCallBlock> toolCalls = new ArrayList<>();

    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        textBuf.append(textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        thinkBuf.append(thinkingBlock.thinking());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        toolCalls.add(toolCallBlock);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "Unsupported assistant content block: " + block.getClass().getSimpleName());
      }
    }

    if (!thinkBuf.isEmpty()) {
      ObjectNode reasoning = inputItems.addObject();
      reasoning.put("type", "reasoning");
      ArrayNode summary = reasoning.putArray("summary");
      ObjectNode summaryItem = summary.addObject();
      summaryItem.put("type", "summary_text");
      summaryItem.put("text", thinkBuf.toString());
    }

    if (!textBuf.isEmpty()) {
      ObjectNode msg = inputItems.addObject();
      msg.put("type", "message");
      msg.put("role", "assistant");
      ArrayNode contentArr = msg.putArray("content");
      ObjectNode contentBlock = contentArr.addObject();
      contentBlock.put("type", "output_text");
      contentBlock.put("text", textBuf.toString());
      conversationContentBlocks.add(contentBlock);
    }

    for (ProviderToolCallBlock call : toolCalls) {
      ObjectNode fc = inputItems.addObject();
      fc.put("type", "function_call");
      fc.put("call_id", call.toolCall().id());
      fc.put("name", call.toolCall().name());
      fc.put("arguments", call.toolCall().argumentsJson());
    }
  }

  private static void encodeToolResultContent(
      ProviderToolResultBlock resultBlock, ObjectNode toolOutput) {
    List<ProviderContentBlock> contents = resultBlock.contents();
    if (contents == null || contents.isEmpty()) {
      toolOutput.put("output", "");
      return;
    }
    if (contents.size() == 1 && contents.get(0) instanceof ProviderTextBlock tb) {
      toolOutput.put("output", tb.text());
      return;
    }

    ArrayNode array = toolOutput.putArray("output");
    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        ObjectNode item = array.addObject();
        item.put("type", "input_text");
        item.put("text", textBlock.text());
      } else if (block instanceof ProviderImageBlock imgBlock) {
        validateImageType(imgBlock.mediaType());
        validateUri(imgBlock.source());
        ObjectNode item = array.addObject();
        item.put("type", "input_image");
        item.put("image_url", imgBlock.source());
        item.put("detail", "auto");
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "Unsupported content block type in tool result: " + block.getClass().getSimpleName());
      }
    }
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
    if (replayState.format() != ProviderReplayFormat.OPENAI_RESPONSES) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid replay format: expected OPENAI_RESPONSES but was " + replayState.format());
    }

    JsonNode payload = replayState.payload();
    ArrayNode outputArray = extractOutputArray(payload);
    validateReplayOutputAgainstDurable(outputArray, durableContents);

    // 代际/前缀校验：失配由 runtime 投影负责，此处回退到语义编码
    if (!replayState.affinity().equals(descriptor.affinity(requestedModel))) {
      return false;
    }
    if (!replayState.sourcePrefixHash().equals(currentPrefixHash)) {
      return false;
    }

    return true;
  }

  private static ArrayNode extractOutputArray(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid replay payload: must be a JSON object");
    }
    JsonNode outputNode = payload.get("output");
    if (outputNode == null || !outputNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid replay payload: missing or non-array 'output' field");
    }
    return (ArrayNode) outputNode;
  }

  private static void validateReplayOutputAgainstDurable(
      ArrayNode outputArray, List<ProviderContentBlock> durableContents) {
    StringBuilder replayText = new StringBuilder();
    StringBuilder replayThinking = new StringBuilder();
    List<String> replayToolCallIds = new ArrayList<>();

    for (JsonNode item : outputArray) {
      if (!item.isObject() || !item.has("type") || !item.get("type").isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid replay output item: missing string type");
      }
      String type = item.get("type").textValue();
      if (!ALLOWED_REPLAY_ITEM_TYPES.contains(type)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "disallowed replay item type in output: " + type);
      }
      switch (type) {
        case "message" -> {
          if (!item.has("role") || !"assistant".equals(item.path("role").asText())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "replay message item must have role='assistant'");
          }
          JsonNode content = item.get("content");
          if (content != null && content.isArray()) {
            for (JsonNode block : content) {
              if (block.isObject() && "output_text".equals(block.path("type").asText())) {
                replayText.append(block.path("text").asText(""));
              }
            }
          }
        }
        case "reasoning" -> {
          JsonNode summary = item.get("summary");
          if (summary != null && summary.isArray()) {
            for (JsonNode s : summary) {
              if (s.isObject() && "summary_text".equals(s.path("type").asText())) {
                replayThinking.append(s.path("text").asText(""));
              }
            }
          }
        }
        case "function_call" -> {
          String callId = item.path("call_id").asText(null);
          if (callId == null || callId.isBlank()) {
            callId = item.path("id").asText(null);
          }
          if (callId != null) {
            replayToolCallIds.add(callId);
          }
        }
        default -> {}
      }
    }

    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<String> durableToolCallIds = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock thb) {
        durableThinking.append(thb.thinking());
      } else if (block instanceof ProviderToolCallBlock tcb) {
        durableToolCallIds.add(tcb.toolCall().id());
      }
    }

    if (!replayText.toString().equals(durableText.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay text content mismatch with durable message content");
    }
    if (!replayToolCallIds.equals(durableToolCallIds)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay tool call IDs mismatch with durable tool calls");
    }
  }

  private static void applyCacheControl(
      ObjectNode root,
      OpenAiPromptCacheMode cacheMode,
      ProviderCacheControl cacheControl,
      List<ObjectNode> systemContentBlocks,
      List<ObjectNode> conversationContentBlocks) {
    if (cacheMode == OpenAiPromptCacheMode.AUTOMATIC) {
      return;
    }

    if (cacheMode == OpenAiPromptCacheMode.LEGACY) {
      if (cacheControl.retention() != PromptCacheRetention.NONE) {
        root.put("prompt_cache_key", cacheControl.affinityKey());
        String retentionValue =
            cacheControl.retention() == PromptCacheRetention.SHORT ? "in_memory" : "24h";
        root.put("prompt_cache_retention", retentionValue);
      }
      return;
    }

    if (cacheMode == OpenAiPromptCacheMode.GPT_5_6_EXPLICIT) {
      ObjectNode options = root.putObject("prompt_cache_options");
      options.put("mode", "explicit");
      options.put("ttl", "30m");

      if (cacheControl.retention() != PromptCacheRetention.NONE) {
        root.put("prompt_cache_key", cacheControl.affinityKey());

        ObjectNode marker = NODES.objectNode();
        marker.put("mode", "explicit");

        if (cacheControl.breakpoints().contains(PromptCacheBreakpoint.SYSTEM)
            && !systemContentBlocks.isEmpty()) {
          ObjectNode lastSysBlock = systemContentBlocks.get(systemContentBlocks.size() - 1);
          lastSysBlock.set("prompt_cache_breakpoint", marker);
        }

        if (cacheControl.breakpoints().contains(PromptCacheBreakpoint.CONVERSATION)
            && !conversationContentBlocks.isEmpty()) {
          ObjectNode lastConvBlock =
              conversationContentBlocks.get(conversationContentBlocks.size() - 1);
          lastConvBlock.set("prompt_cache_breakpoint", marker);
        }
      }
    }
  }

  private static void validateImageType(String mediaType) {
    if (mediaType == null || !ALLOWED_IMAGE_TYPES.contains(mediaType.toLowerCase(Locale.ROOT))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Unsupported image media type: "
              + mediaType
              + ". Supported types: "
              + ALLOWED_IMAGE_TYPES);
    }
  }

  private static void validateDocumentType(String mediaType) {
    if (mediaType == null || !ALLOWED_DOCUMENT_TYPE.equalsIgnoreCase(mediaType)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Unsupported document media type: " + mediaType + ". Only application/pdf is supported");
    }
  }

  private static void validateUri(String source) {
    if (source == null || source.isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source must not be blank");
    }
    if (source.startsWith("data:")) {
      int comma = source.indexOf(',');
      if (comma < 0) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid data URI format");
      }
      return;
    }
    try {
      URI uri = URI.create(source);
      String scheme = uri.getScheme();
      if (scheme == null
          || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "media source URL must use http or https scheme");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "media source URL must contain a valid host");
      }
    } catch (IllegalArgumentException e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source is not a valid URI");
    }
  }
}
