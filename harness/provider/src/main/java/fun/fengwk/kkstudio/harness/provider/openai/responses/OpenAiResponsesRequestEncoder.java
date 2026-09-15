package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.RequestBodySizeGuard;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
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
import java.util.Iterator;
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

  /** OpenAI Responses 拒绝低于 16 的 max_output_tokens；该下限只属于本 Provider。 */
  private static final int OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16;

  private static final Set<String> ALLOWED_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final String ALLOWED_DOCUMENT_TYPE = "application/pdf";
  private static final Set<String> ALLOWED_PAYLOAD_FIELDS = Set.of("output");
  private static final Set<String> ALLOWED_REPLAY_ITEM_TYPES =
      Set.of("message", "reasoning", "function_call");
  private static final Set<String> ALLOWED_MESSAGE_FIELDS =
      Set.of("type", "role", "content", "id", "phase");
  private static final Set<String> ALLOWED_MESSAGE_CONTENT_FIELDS = Set.of("type", "text");
  private static final Set<String> ALLOWED_REASONING_FIELDS =
      Set.of("type", "summary", "encrypted_content", "id");
  private static final Set<String> ALLOWED_SUMMARY_FIELDS = Set.of("type", "text");
  private static final Set<String> ALLOWED_FUNCTION_CALL_FIELDS =
      Set.of("type", "call_id", "id", "name", "arguments");

  /** 应用层最终 UTF-8 请求体字节上限守卫；默认使用共享的 192 MiB 应用上限。 */
  private final RequestBodySizeGuard bodySizeGuard;

  OpenAiResponsesRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  OpenAiResponsesRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  OpenAiResponsesEncodedRequest encode(
      ProviderRequest request, ProviderDescriptor descriptor, OpenAiResponsesConfig config) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(config, "config");

    ObjectNode root = NODES.objectNode();
    root.put("model", request.model().modelId());
    root.put("stream", true);
    root.put("store", false);

    applyOutputBudget(root, request.outputTokens());
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
          request.model(),
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

    bodySizeGuard.enforce(utf8Bytes);

    return new OpenAiResponsesEncodedRequest(utf8Bytes, sourcePrefixHash);
  }

  /** 输出预算低于本 Provider 的协议下限 {@code 16} 时明确拒绝；合法预算原样编码，绝不擅自扩大冻结值。 */
  private static void applyOutputBudget(ObjectNode root, int outputTokens) {
    if (outputTokens < OPENAI_RESPONSES_MIN_OUTPUT_TOKENS) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "max_output_tokens must be at least 16");
    }
    root.put("max_output_tokens", outputTokens);
  }

  /**
   * reasoning effort 编码：{@code off} 映射为协议关闭值 {@code none}（仅发送 effort，不带 summary/encrypted content），
   * 其他厂商定义值原样下发并请求摘要；null 不发送推理字段，由服务端默认决定。
   */
  private static void applyReasoningParameters(ObjectNode root, ModelVariant variant) {
    if (variant == null || variant.reasoningEffort() == null) {
      return;
    }
    ObjectNode reasoning = root.putObject("reasoning");
    if (variant.reasoningOff()) {
      reasoning.put("effort", "none");
      return;
    }
    reasoning.put("effort", variant.reasoningEffort());
    reasoning.put("summary", "auto");
    ArrayNode include = root.putArray("include");
    include.add("reasoning.encrypted_content");
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
      node.set("parameters", encodeStrictParameters(tool));
      node.put("strict", true);
    }
    return array;
  }

  /** 解析工具参数 schema 并归一化为 strict 形态；共享的 inputSchemaJson 绝不被修改。 */
  private static ObjectNode encodeStrictParameters(ProviderToolDefinition tool) {
    JsonNode schemaNode;
    try {
      schemaNode = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool inputSchemaJson is not valid JSON");
    }
    if (schemaNode == null || !schemaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool parameters must be a JSON object");
    }
    return OpenAiResponsesStrictSchema.normalize((ObjectNode) schemaNode);
  }

  private static void encodeMessage(
      ProviderMessage msg,
      ProviderDescriptor descriptor,
      ModelDescriptor model,
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
        // 推理模型把系统指令编码为 developer message；非推理模型保持既有 system 行为。
        sysMsg.put("role", model.reasoning() ? "developer" : "system");
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
              replayState, descriptor, model.modelId(), currentPrefixHash, msg.contents())) {
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
      ProviderToolCall tc = call.toolCall();
      parseJsonObject(tc.argumentsJson(), "toolCall argumentsJson must be a JSON object");
      ObjectNode fc = inputItems.addObject();
      fc.put("type", "function_call");
      fc.put("call_id", tc.id());
      fc.put("name", tc.name());
      fc.put("arguments", tc.argumentsJson());
    }
  }

  private static void encodeToolResultContent(
      ProviderToolResultBlock resultBlock, ObjectNode toolOutput) {
    List<ProviderContentBlock> contents = resultBlock.contents();
    if (contents == null || contents.isEmpty()) {
      toolOutput.put("output", "");
      return;
    }
    if (contents.size() == 1) {
      ProviderContentBlock single = contents.get(0);
      if (single instanceof ProviderTextBlock textBlock) {
        toolOutput.put("output", textBlock.text());
        return;
      }
      if (single instanceof ProviderJsonBlock jsonBlock) {
        toolOutput.put("output", jsonBlock.json());
        return;
      }
      if (!(single instanceof ProviderImageBlock)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "Unsupported content block type in tool result: " + single.getClass().getSimpleName());
      }
    }

    ArrayNode array = toolOutput.putArray("output");
    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        ObjectNode item = array.addObject();
        item.put("type", "input_text");
        item.put("text", textBlock.text());
      } else if (block instanceof ProviderJsonBlock jsonBlock) {
        ObjectNode item = array.addObject();
        item.put("type", "input_text");
        item.put("text", jsonBlock.json());
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
      return false;
    }

    JsonNode payload = replayState.payload();
    ArrayNode outputArray = extractOutputArray(payload);
    validateReplayOutputAgainstDurable(outputArray, durableContents);

    // 代际/前缀校验：失配回退到语义编码
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
    validateAllowedFields(payload, ALLOWED_PAYLOAD_FIELDS, "replay payload");
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
    List<ProviderToolCall> replayToolCalls = new ArrayList<>();

    for (JsonNode item : outputArray) {
      if (!item.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid replay output item: must be a JSON object");
      }
      if (!item.has("type")
          || !item.get("type").isTextual()
          || item.get("type").textValue().isBlank()) {
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
          validateAllowedFields(item, ALLOWED_MESSAGE_FIELDS, "message item");
          if (!item.has("role")
              || !item.get("role").isTextual()
              || !"assistant".equals(item.get("role").textValue())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "replay message item must have role='assistant'");
          }
          if (item.has("id")) {
            if (!item.get("id").isTextual() || item.get("id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "replay message id must be non-blank string");
            }
          }
          if (item.has("phase")) {
            if (!item.get("phase").isTextual() || item.get("phase").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message phase must be non-blank string");
            }
          }
          JsonNode content = item.get("content");
          if (content == null || !content.isArray()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "replay message content must be an array");
          }
          for (JsonNode block : content) {
            if (!block.isObject()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message content block must be an object");
            }
            validateAllowedFields(block, ALLOWED_MESSAGE_CONTENT_FIELDS, "message content block");
            if (!block.has("type")
                || !block.get("type").isTextual()
                || !"output_text".equals(block.get("type").textValue())) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message content block type must be 'output_text'");
            }
            if (!block.has("text") || !block.get("text").isTextual()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message content block must have string text");
            }
            replayText.append(block.get("text").textValue());
          }
        }
        case "reasoning" -> {
          validateAllowedFields(item, ALLOWED_REASONING_FIELDS, "reasoning item");
          if (item.has("id")) {
            if (!item.get("id").isTextual() || item.get("id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay reasoning id must be non-blank string");
            }
          }
          boolean itemHasEncrypted = false;
          if (item.has("encrypted_content")) {
            JsonNode encNode = item.get("encrypted_content");
            if (!encNode.isTextual() || encNode.textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay reasoning encrypted_content must be non-blank string");
            }
            itemHasEncrypted = true;
          }
          boolean itemHasSummary = false;
          if (item.has("summary")) {
            JsonNode summary = item.get("summary");
            if (!summary.isArray()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "replay reasoning summary must be an array");
            }
            for (JsonNode s : summary) {
              if (!s.isObject()) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_REQUEST,
                    "replay reasoning summary block must be an object");
              }
              validateAllowedFields(s, ALLOWED_SUMMARY_FIELDS, "reasoning summary block");
              if (!s.has("type")
                  || !s.get("type").isTextual()
                  || !"summary_text".equals(s.get("type").textValue())) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_REQUEST,
                    "replay reasoning summary block type must be 'summary_text'");
              }
              if (!s.has("text") || !s.get("text").isTextual()) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_REQUEST,
                    "replay reasoning summary block must have string text");
              }
              replayThinking.append(s.get("text").textValue());
              itemHasSummary = true;
            }
          }
          if (!itemHasEncrypted && !itemHasSummary) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "replay reasoning must contain either encrypted_content or non-empty summary");
          }
        }
        case "function_call" -> {
          validateAllowedFields(item, ALLOWED_FUNCTION_CALL_FIELDS, "function_call item");
          String callId = null;
          if (item.has("call_id")) {
            if (!item.get("call_id").isTextual() || item.get("call_id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "function_call call_id must be non-blank string");
            }
            callId = item.get("call_id").textValue();
          }
          if (item.has("id")) {
            if (!item.get("id").isTextual() || item.get("id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "function_call id must be non-blank string");
            }
            if (callId == null) {
              callId = item.get("id").textValue();
            }
          }
          if (callId == null || callId.isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "function_call missing call_id or id");
          }
          if (!item.has("name")
              || !item.get("name").isTextual()
              || item.get("name").textValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "function_call missing non-blank string name");
          }
          String name = item.get("name").textValue();
          if (!item.has("arguments") || !item.get("arguments").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "function_call missing string arguments");
          }
          String args = item.get("arguments").textValue();
          parseJsonObject(args, "function_call arguments must be a JSON object");
          replayToolCalls.add(new ProviderToolCall(callId, name, args));
        }
        default -> throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unsupported replay item type: " + type);
      }
    }

    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableToolCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock thb) {
        durableThinking.append(thb.thinking());
      } else if (block instanceof ProviderToolCallBlock tcb) {
        ProviderToolCall tc = tcb.toolCall();
        parseJsonObject(tc.argumentsJson(), "durable tool call arguments must be a JSON object");
        durableToolCalls.add(tc);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "durable content block not supported in assistant message replay: "
                + block.getClass().getSimpleName());
      }
    }

    if (!replayText.toString().equals(durableText.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay text content mismatch with durable message content");
    }
    if (!durableThinking.isEmpty()) {
      if (!replayThinking.toString().equals(durableThinking.toString())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay thinking content mismatch with durable message thinking");
      }
    } else {
      if (!replayThinking.isEmpty()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay thinking content mismatch with durable message thinking");
      }
    }
    if (replayToolCalls.size() != durableToolCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay tool calls count mismatch with durable tool calls");
    }
    for (int i = 0; i < replayToolCalls.size(); i++) {
      ProviderToolCall rc = replayToolCalls.get(i);
      ProviderToolCall dc = durableToolCalls.get(i);
      if (!rc.id().equals(dc.id())
          || !rc.name().equals(dc.name())
          || !rc.argumentsJson().equals(dc.argumentsJson())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay tool call mismatch with durable tool call at index " + i);
      }
    }
  }

  private static void validateAllowedFields(
      JsonNode node, Set<String> allowedFields, String context) {
    Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowedFields.contains(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "disallowed field '" + field + "' in " + context);
      }
    }
  }

  private static void applyCacheControl(
      ObjectNode root,
      OpenAiPromptCacheMode cacheMode,
      ProviderCacheControl cacheControl,
      List<ObjectNode> systemContentBlocks,
      List<ObjectNode> conversationContentBlocks) {
    if (cacheMode == OpenAiPromptCacheMode.AUTOMATIC) {
      // AUTOMATIC: 不发任何 cache hint（防御性忽略可能传入的非 none cacheControl），匹配 OpenAI Chat 行为
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
