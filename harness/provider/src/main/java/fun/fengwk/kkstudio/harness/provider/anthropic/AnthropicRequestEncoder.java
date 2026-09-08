package fun.fengwk.kkstudio.harness.provider.anthropic;

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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Anthropic Messages 协议的请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 Anthropic 规范的 UTF-8 请求 JSON 字节数组， 并提取冻结的
 * sourcePrefixHash。
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
  private static final Set<String> ALLOWED_REPLAY_CONTENT_TYPES =
      Set.of("text", "thinking", "redacted_thinking", "tool_use");

  private static final int DEFAULT_MAX_TOKENS = 1024;
  private static final int MAX_CACHE_BREAKPOINTS = 3;
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

    validatePenalties(request.variant());
    validateCacheControl(request.cacheControl());

    ObjectNode root = NODES.objectNode();
    root.put("model", request.model().modelName());

    int maxTokens = DEFAULT_MAX_TOKENS;
    if (request.variant() != null && request.variant().maxOutputTokens() != null) {
      maxTokens = request.variant().maxOutputTokens();
    }
    root.put("max_tokens", maxTokens);
    root.put("stream", true);

    applySamplingParameters(root, request.variant());
    applyReasoningParameters(root, request, config, maxTokens);

    ArrayNode toolsArray = encodeTools(request.tools());

    // 提取 leading SYSTEM 与 conversation messages
    List<ProviderMessage> messages = request.messages();
    List<ProviderContentBlock> leadingSystemBlocks = new ArrayList<>();
    List<ProviderMessage> conversationMessages = new ArrayList<>();
    boolean seenNonSystem = false;

    for (ProviderMessage msg : messages) {
      if (msg.role() == ProviderMessageRole.SYSTEM) {
        if (seenNonSystem) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "Anthropic does not allow mid-conversation SYSTEM messages");
        }
        leadingSystemBlocks.addAll(msg.contents());
      } else {
        seenNonSystem = true;
        conversationMessages.add(msg);
      }
    }

    ArrayNode unmarkedSystemArray = null;
    if (!leadingSystemBlocks.isEmpty()) {
      unmarkedSystemArray = NODES.arrayNode();
      for (ProviderContentBlock block : leadingSystemBlocks) {
        unmarkedSystemArray.add(encodeSystemBlock(block));
      }
    }

    // 从左到右构建 wire messages 并维护 prefix hash
    ArrayNode wireMessagesArray = NODES.arrayNode();
    for (ProviderMessage msg : conversationMessages) {
      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        String currentPrefixHash =
            AnthropicPrefixHasher.calculateHash(unmarkedSystemArray, toolsArray, wireMessagesArray);
        ObjectNode assistantWireMessage =
            encodeAssistantMessage(msg, descriptor, request.model().modelName(), currentPrefixHash);
        wireMessagesArray.add(assistantWireMessage);
      } else if (msg.role() == ProviderMessageRole.USER) {
        wireMessagesArray.add(encodeUserMessage(msg));
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        wireMessagesArray.add(encodeToolMessage(msg));
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unexpected message role: " + msg.role());
      }
    }

    // 冻结当前请求新 assistant 生成前的 sourcePrefixHash
    String frozenSourcePrefixHash =
        AnthropicPrefixHasher.calculateHash(unmarkedSystemArray, toolsArray, wireMessagesArray);

    // 注入 cache marker（排它于 prefix hash）
    applyCacheMarkers(request.cacheControl(), toolsArray, unmarkedSystemArray, wireMessagesArray);

    if (toolsArray != null && !toolsArray.isEmpty()) {
      root.set("tools", toolsArray);
    }
    if (unmarkedSystemArray != null && !unmarkedSystemArray.isEmpty()) {
      root.set("system", unmarkedSystemArray);
    }
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

    return new AnthropicEncodedRequest(utf8Bytes, frozenSourcePrefixHash);
  }

  private static void validatePenalties(ModelVariant variant) {
    if (variant == null) {
      return;
    }
    if (variant.frequencyPenalty() != null || variant.presencePenalty() != null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Anthropic does not support frequencyPenalty or presencePenalty");
    }
  }

  private static void validateCacheControl(ProviderCacheControl cacheControl) {
    if (cacheControl.retention() == PromptCacheRetention.NONE) {
      return;
    }
    if (cacheControl.breakpoints().isEmpty()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Anthropic prompt cache control requires at least one breakpoint (SYSTEM, TOOLS, CONVERSATION)");
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
    if (variant.topK() != null) {
      root.put("top_k", variant.topK());
    }
    if (!variant.stopSequences().isEmpty()) {
      ArrayNode stopSeqs = root.putArray("stop_sequences");
      for (String seq : variant.stopSequences()) {
        stopSeqs.add(seq);
      }
    }
  }

  private static void applyReasoningParameters(
      ObjectNode root, ProviderRequest request, AnthropicConfiguration config, int maxTokens) {
    if (!request.model().reasoning()
        || request.variant() == null
        || request.variant().reasoningEffort() == null) {
      return;
    }

    AnthropicThinkingMode mode = config.anthropicThinkingMode();
    switch (mode) {
      case ADAPTIVE -> {
        ObjectNode thinking = root.putObject("thinking");
        thinking.put("type", "adaptive");

        ObjectNode outputConfig = root.putObject("output_config");
        outputConfig.put("effort", request.variant().reasoningEffort());
      }
      case BUDGET -> {
        String effort = request.variant().reasoningEffort();
        int budgetTokens = mapBudgetTokens(effort);
        if (budgetTokens >= maxTokens) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "budget_tokens must be strictly lower than max_tokens");
        }
        ObjectNode thinking = root.putObject("thinking");
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", budgetTokens);
      }
    }
  }

  private static int mapBudgetTokens(String effort) {
    if (effort == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported reasoning effort for budget thinking");
    }
    return switch (effort) {
      case "minimal" -> 1024;
      case "low" -> 2048;
      case "medium" -> 8192;
      case "high" -> 16384;
      default -> throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported reasoning effort for budget thinking");
    };
  }

  private static ArrayNode encodeTools(List<ProviderToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return NODES.arrayNode();
    }
    ArrayNode array = NODES.arrayNode();
    for (ProviderToolDefinition tool : tools) {
      ObjectNode toolNode = array.addObject();
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
    }
    return array;
  }

  private static ObjectNode encodeSystemBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderDocumentBlock documentBlock) {
      return encodeDocumentBlock(documentBlock);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported system content block type");
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

  private static ObjectNode encodeAssistantMessage(
      ProviderMessage message,
      ProviderDescriptor descriptor,
      String requestedModel,
      String currentPrefixHash) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "assistant");

    ProviderReplayState replayState = message.replayState();
    if (canReplay(replayState, descriptor, requestedModel, currentPrefixHash, message.contents())) {
      ArrayNode replayedBlocks = NODES.arrayNode();
      for (JsonNode blockNode : replayState.payload().path("content")) {
        replayedBlocks.add(blockNode.deepCopy());
      }
      msgNode.set("content", replayedBlocks);
      return msgNode;
    }

    // Semantic fallback
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      contents.add(encodeAssistantFallbackBlock(block));
    }
    return msgNode;
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
    if (replayState.format() != ProviderReplayFormat.ANTHROPIC_MESSAGES) {
      return false;
    }
    if (!replayState.affinity().equals(descriptor.affinity(requestedModel))) {
      return false;
    }
    if (!replayState.sourcePrefixHash().equals(currentPrefixHash)) {
      return false;
    }
    JsonNode payload = replayState.payload();
    if (!payload.isObject()
        || payload.size() != 2
        || !payload.has("role")
        || !payload.get("role").isTextual()
        || !"assistant".equals(payload.get("role").textValue())
        || !payload.has("content")
        || !payload.get("content").isArray()) {
      return false;
    }
    ArrayNode contentArray = (ArrayNode) payload.get("content");
    return validatePayloadAgainstDurable(contentArray, durableContents);
  }

  private static boolean validatePayloadAgainstDurable(
      ArrayNode contentArray, List<ProviderContentBlock> durableContents) {
    StringBuilder payloadText = new StringBuilder();
    StringBuilder payloadThinking = new StringBuilder();
    List<ProviderToolCall> payloadCalls = new ArrayList<>();

    for (JsonNode item : contentArray) {
      if (!item.isObject() || !item.has("type") || !item.get("type").isTextual()) {
        return false;
      }
      String type = item.get("type").textValue();
      if (!ALLOWED_REPLAY_CONTENT_TYPES.contains(type)) {
        return false;
      }
      switch (type) {
        case "text" -> {
          if (item.size() != 2 || !item.has("text") || !item.get("text").isTextual()) {
            return false;
          }
          payloadText.append(item.get("text").textValue());
        }
        case "thinking" -> {
          if (item.size() != 3
              || !item.has("thinking")
              || !item.get("thinking").isTextual()
              || !item.has("signature")
              || !item.get("signature").isTextual()
              || item.get("signature").textValue().isBlank()) {
            return false;
          }
          payloadThinking.append(item.get("thinking").textValue());
        }
        case "redacted_thinking" -> {
          if (item.size() != 2
              || !item.has("data")
              || !item.get("data").isTextual()
              || item.get("data").textValue().isBlank()) {
            return false;
          }
        }
        case "tool_use" -> {
          if (item.size() != 4
              || !item.has("id")
              || !item.get("id").isTextual()
              || item.get("id").textValue().isBlank()
              || !item.has("name")
              || !item.get("name").isTextual()
              || item.get("name").textValue().isBlank()
              || !item.has("input")
              || !item.get("input").isObject()) {
            return false;
          }
          payloadCalls.add(
              new ProviderToolCall(
                  item.get("id").textValue(),
                  item.get("name").textValue(),
                  item.get("input").toString()));
        }
        default -> {
          return false;
        }
      }
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
        durableCalls.add(cb.toolCall());
      } else {
        return false;
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
      if (!pCall.id().equals(dCall.id()) || !pCall.name().equals(dCall.name())) {
        return false;
      }
      try {
        JsonNode pInput = OBJECT_MAPPER.readTree(pCall.argumentsJson());
        JsonNode dInput = OBJECT_MAPPER.readTree(dCall.argumentsJson());
        if (!pInput.equals(dInput)) {
          return false;
        }
      } catch (JsonProcessingException exception) {
        return false;
      }
    }
    return true;
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
      JsonNode inputNode;
      try {
        inputNode = OBJECT_MAPPER.readTree(call.argumentsJson());
      } catch (JsonProcessingException exception) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "toolCall argumentsJson is not valid JSON");
      }
      if (!inputNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool_use input must be a JSON object");
      }
      node.set("input", inputNode);
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported assistant block type");
  }

  private static void applyCacheMarkers(
      ProviderCacheControl cacheControl,
      ArrayNode toolsArray,
      ArrayNode systemArray,
      ArrayNode wireMessagesArray) {
    if (cacheControl == null
        || cacheControl.retention() == PromptCacheRetention.NONE
        || cacheControl.breakpoints().isEmpty()) {
      return;
    }

    ObjectNode marker = NODES.objectNode();
    marker.put("type", "ephemeral");
    if (cacheControl.retention() == PromptCacheRetention.LONG) {
      marker.put("ttl", "1h");
    }

    int markersPlaced = 0;

    // 1. TOOLS: 最后一个合格 tool
    if (cacheControl.breakpoints().contains(PromptCacheBreakpoint.TOOLS)
        && toolsArray != null
        && !toolsArray.isEmpty()) {
      JsonNode lastTool = toolsArray.get(toolsArray.size() - 1);
      if (lastTool.isObject()) {
        ((ObjectNode) lastTool).set("cache_control", marker);
        markersPlaced++;
      }
    }

    // 2. SYSTEM: 最后一个合格 system block
    if (markersPlaced < MAX_CACHE_BREAKPOINTS
        && cacheControl.breakpoints().contains(PromptCacheBreakpoint.SYSTEM)
        && systemArray != null
        && !systemArray.isEmpty()) {
      JsonNode lastSystemBlock = systemArray.get(systemArray.size() - 1);
      if (lastSystemBlock.isObject()) {
        ((ObjectNode) lastSystemBlock).set("cache_control", marker);
        markersPlaced++;
      }
    }

    // 3. CONVERSATION: 最新合格 conversation block（排除 thinking / redacted_thinking）
    if (markersPlaced < MAX_CACHE_BREAKPOINTS
        && cacheControl.breakpoints().contains(PromptCacheBreakpoint.CONVERSATION)
        && wireMessagesArray != null
        && !wireMessagesArray.isEmpty()) {
      markLatestEligibleConversationBlock(wireMessagesArray, marker);
    }
  }

  private static void markLatestEligibleConversationBlock(
      ArrayNode wireMessagesArray, ObjectNode marker) {
    for (int m = wireMessagesArray.size() - 1; m >= 0; m--) {
      JsonNode msgNode = wireMessagesArray.get(m);
      if (!msgNode.isObject() || !msgNode.has("content")) {
        continue;
      }
      JsonNode contentNode = msgNode.get("content");
      if (contentNode.isArray()) {
        ArrayNode contents = (ArrayNode) contentNode;
        for (int c = contents.size() - 1; c >= 0; c--) {
          JsonNode block = contents.get(c);
          if (block.isObject()) {
            String type = block.path("type").asText();
            if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) {
              ((ObjectNode) block).set("cache_control", marker);
              return;
            }
          }
        }
      }
    }
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
}
