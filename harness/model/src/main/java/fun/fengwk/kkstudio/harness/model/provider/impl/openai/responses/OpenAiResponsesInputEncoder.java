package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderMediaValidator;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 把运行时消息编码为 Responses 的 {@code input} items：用户内容、assistant replay/语义回退与工具结果。 */
final class OpenAiResponsesInputEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiResponsesInputEncoder() {}

  static ArrayNode encode(
      List<ProviderMessage> messages, ProviderDescriptor descriptor, ModelDescriptor model) {
    ArrayNode inputItems = NODES.arrayNode();
    for (ProviderMessage message : messages) {
      encodeMessage(message, descriptor, model, inputItems);
    }
    return inputItems;
  }

  private static void encodeMessage(
      ProviderMessage message,
      ProviderDescriptor descriptor,
      ModelDescriptor model,
      ArrayNode inputItems) {
    Objects.requireNonNull(message, "msg");
    switch (message.role()) {
      case USER -> encodeUserMessage(message, inputItems);
      case ASSISTANT -> encodeAssistantMessage(message, descriptor, model, inputItems);
      case SYSTEM -> throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "mid-conversation system messages are unsupported by OpenAI Responses");
      case TOOL -> encodeToolMessage(message, inputItems);
    }
  }

  private static void encodeUserMessage(ProviderMessage message, ArrayNode inputItems) {
    ObjectNode userMessage = inputItems.addObject();
    userMessage.put("type", "message");
    userMessage.put("role", "user");
    ArrayNode contents = userMessage.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      contents.add(encodeUserContentBlock(block));
    }
  }

  private static void encodeAssistantMessage(
      ProviderMessage message,
      ProviderDescriptor descriptor,
      ModelDescriptor model,
      ArrayNode inputItems) {
    List<ObjectNode> replayItems =
        OpenAiResponsesReplayEncoder.build(descriptor, model.modelId(), message.contents());
    if (replayItems != null) {
      inputItems.addAll(replayItems);
      return;
    }
    encodeAssistantSemanticFallback(message.contents(), inputItems);
  }

  /**
   * 语义回退：文本、思考与 JSON 诊断按序合并为 assistant message，工具调用编码为独立的 {@code function_call} item。
   *
   * <p>Opaque 与 redacted thinking 没有跨模型的语义投影，直接忽略。
   */
  private static void encodeAssistantSemanticFallback(
      List<ProviderContentBlock> contents, ArrayNode inputItems) {
    StringBuilder text = new StringBuilder();
    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        text.append(textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        if (thinkingBlock.thinking() != null) {
          text.append(thinkingBlock.thinking());
        }
      } else if (block instanceof ProviderJsonBlock jsonBlock) {
        text.append(jsonBlock.json());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        flushAssistantText(text, inputItems);
        inputItems.add(encodeFunctionCall(toolCallBlock.toolCall()));
      } else if (!(block instanceof ProviderOpaqueBlock)
          && !(block instanceof ProviderRedactedThinkingBlock)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "Unsupported assistant content block: " + block.getClass().getSimpleName());
      }
    }
    flushAssistantText(text, inputItems);
  }

  private static ObjectNode encodeFunctionCall(ProviderToolCall call) {
    ProviderJsonHelper.parseStrictJsonObject(
        call.argumentsJson(), "toolCall argumentsJson must be a JSON object");
    ObjectNode item = NODES.objectNode();
    item.put("type", "function_call");
    item.put("call_id", call.id());
    item.put("name", call.name());
    item.put("arguments", call.argumentsJson());
    return item;
  }

  private static void flushAssistantText(StringBuilder text, ArrayNode inputItems) {
    if (text.isEmpty()) {
      return;
    }
    ObjectNode message = inputItems.addObject();
    message.put("type", "message");
    message.put("role", "assistant");
    ObjectNode content = message.putArray("content").addObject();
    content.put("type", "output_text");
    content.put("text", text.toString());
    text.setLength(0);
  }

  private static void encodeToolMessage(ProviderMessage message, ArrayNode inputItems) {
    for (ProviderContentBlock block : message.contents()) {
      if (!(block instanceof ProviderToolResultBlock resultBlock)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "TOOL message must only contain ProviderToolResultBlock");
      }
      ObjectNode toolOutput = inputItems.addObject();
      toolOutput.put("type", "function_call_output");
      toolOutput.put("call_id", resultBlock.toolCallId());
      encodeToolResultContent(resultBlock, toolOutput);
    }
  }

  /**
   * 工具结果编码：单文本/JSON 保持简洁的 string 形态，含媒体时使用 {@code output} 数组。
   *
   * <p>数组元素只允许 {@code input_text}、{@code input_image} 与 {@code input_file}（PDF）：OpenAI Responses 的
   * {@code function_call_output.output} 明确支持这三种输入项。音频、视频与任何其他块类型都以 {@code INVALID_REQUEST} 明确失败，
   * 绝不静默丢弃媒体。
   */
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
    }
    ArrayNode array = toolOutput.putArray("output");
    for (ProviderContentBlock block : contents) {
      array.add(encodeToolResultItem(block));
    }
  }

  /** 工具结果的文本与 JSON 统一编码为 {@code input_text}，媒体与用户内容共用同一规则，保证同模态两处 wire 形态一致。 */
  private static ObjectNode encodeToolResultItem(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode item = NODES.objectNode();
      item.put("type", "input_text");
      item.put("text", textBlock.text());
      return item;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      ObjectNode item = NODES.objectNode();
      item.put("type", "input_text");
      item.put("text", jsonBlock.json());
      return item;
    }
    if (block instanceof ProviderImageBlock || block instanceof ProviderDocumentBlock) {
      return encodeUserContentBlock(block);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "OpenAI Responses does not support tool result content block type: "
            + block.getClass().getSimpleName());
  }

  static ObjectNode encodeUserContentBlock(ProviderContentBlock block) {
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
    if (block instanceof ProviderDocumentBlock documentBlock) {
      validateDocumentType(documentBlock.mediaType());
      validateUri(documentBlock.source());
      return encodeDocumentBlock(documentBlock);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "OpenAI Responses does not support content block type: "
            + block.getClass().getSimpleName());
  }

  private static ObjectNode encodeDocumentBlock(ProviderDocumentBlock block) {
    ObjectNode node = NODES.objectNode();
    node.put("type", "input_file");
    if (block.source().startsWith("data:")) {
      node.put("file_data", block.source());
      node.put("filename", "document.pdf");
    } else {
      node.put("file_url", block.source());
    }
    return node;
  }

  private static void validateImageType(String mediaType) {
    if (mediaType == null
        || !ProviderMediaValidator.isAllowedMediaType(
            mediaType.toLowerCase(Locale.ROOT), ProviderMediaValidator.IMAGE_MEDIA_TYPES)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Unsupported image media type: "
              + mediaType
              + ". Supported types: "
              + ProviderMediaValidator.IMAGE_MEDIA_TYPES);
    }
  }

  private static void validateDocumentType(String mediaType) {
    if (!ProviderMediaValidator.isAllowedMediaType(
        mediaType == null ? null : mediaType.toLowerCase(Locale.ROOT),
        ProviderMediaValidator.DOCUMENT_MEDIA_TYPES)) {
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
      if (source.indexOf(',') < 0) {
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
    } catch (IllegalArgumentException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source is not a valid URI");
    }
  }
}
