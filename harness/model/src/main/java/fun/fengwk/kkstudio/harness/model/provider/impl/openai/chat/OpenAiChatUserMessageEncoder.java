package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;

import java.util.Locale;

/** 把 USER 消息编码为 OpenAI Chat 的 wire 形态：纯文本用字符串，其余块用 parts 数组。 */
final class OpenAiChatUserMessageEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiChatUserMessageEncoder() {}

  static ObjectNode encode(ProviderMessage message) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "user");

    // 检查是否包含媒体块或仅单一文本
    boolean hasNonText = false;
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderVideoBlock) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "OpenAI chat does not support VIDEO");
      }
      if (!(block instanceof ProviderTextBlock)) {
        hasNonText = true;
      }
    }

    if (!hasNonText && message.contents().size() == 1) {
      ProviderTextBlock textBlock = (ProviderTextBlock) message.contents().get(0);
      msgNode.put("content", textBlock.text());
      return msgNode;
    }

    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      contents.add(encodeContentPart(block));
    }
    return msgNode;
  }

  private static ObjectNode encodeContentPart(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      return encodeTextPart(textBlock);
    }
    if (block instanceof ProviderImageBlock imageBlock) {
      return encodeImagePart(imageBlock);
    }
    if (block instanceof ProviderAudioBlock audioBlock) {
      return encodeAudioPart(audioBlock);
    }
    if (block instanceof ProviderDocumentBlock documentBlock) {
      return encodeDocumentPart(documentBlock);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "unsupported block in user message: " + block.getClass().getSimpleName());
  }

  private static ObjectNode encodeTextPart(ProviderTextBlock block) {
    ObjectNode textPart = NODES.objectNode();
    textPart.put("type", "text");
    textPart.put("text", block.text());
    return textPart;
  }

  private static ObjectNode encodeImagePart(ProviderImageBlock block) {
    ObjectNode imagePart = NODES.objectNode();
    imagePart.put("type", "image_url");
    imagePart.putObject("image_url").put("url", block.source());
    return imagePart;
  }

  /** 音频只接受内联 base64（data URI 或裸 base64）；远端 URL 必须由调用方先下载，本协议不做抓取。 */
  private static ObjectNode encodeAudioPart(ProviderAudioBlock block) {
    String source = block.source().trim();
    if (source.startsWith("http://") || source.startsWith("https://")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "OpenAI chat audio requires base64 data; downloading or fabricating from URL is not supported");
    }
    String data;
    String format;
    if (source.startsWith("data:")) {
      int commaIdx = source.indexOf(',');
      if (commaIdx == -1) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid data URI for audio");
      }
      data = source.substring(commaIdx + 1);
      format = extractAudioFormat(source.substring(0, commaIdx));
    } else {
      data = source;
      format = extractAudioFormat(block.mediaType());
    }
    ObjectNode audioPart = NODES.objectNode();
    audioPart.put("type", "input_audio");
    ObjectNode inputAudio = audioPart.putObject("input_audio");
    inputAudio.put("data", data);
    inputAudio.put("format", format);
    return audioPart;
  }

  private static ObjectNode encodeDocumentPart(ProviderDocumentBlock block) {
    String mime = block.mediaType().toLowerCase(Locale.ROOT);
    if (!mime.contains("pdf")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "unsupported document media type: " + block.mediaType());
    }
    String fileData = block.source();
    if (!fileData.startsWith("data:")
        && !fileData.startsWith("http://")
        && !fileData.startsWith("https://")) {
      fileData = "data:application/pdf;base64," + fileData;
    }
    ObjectNode documentPart = NODES.objectNode();
    documentPart.put("type", "file");
    ObjectNode file = documentPart.putObject("file");
    file.put("file_data", fileData);
    file.put("filename", "pdf_file");
    return documentPart;
  }

  private static String extractAudioFormat(String mimeOrHeader) {
    String lower = mimeOrHeader.toLowerCase(Locale.ROOT);
    if (lower.contains("wav")) {
      return "wav";
    }
    if (lower.contains("mp3") || lower.contains("mpeg")) {
      return "mp3";
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported audio format: " + mimeOrHeader);
  }
}
