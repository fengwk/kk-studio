package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderMediaValidator;

import java.net.URI;

/**
 * Anthropic Messages 的 user 与 tool 消息编码，以及其中复用的媒体 block 编码。
 *
 * <p>媒体来源只接受与声明 MIME 一致的内联 Base64 data URI；图片额外接受 http(s) URL。文档没有 URL 形态，必须在编码期 fail closed。
 */
final class AnthropicContentEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private AnthropicContentEncoder() {}

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

  static ObjectNode encodeUserMessage(ProviderMessage message) {
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

  static ObjectNode encodeToolMessage(ProviderMessage message) {
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
}
