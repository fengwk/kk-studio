package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderMediaValidator;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gemini GenerateContent 的 user 内容与工具结果编码。
 *
 * <p>媒体来源可以是内联 Base64 data URI 或 {@code http}/{@code https}/{@code gs} fileUri。工具结果的媒体必须内联在 {@code
 * functionResponse.parts[].inlineData} 内：作为外层 {@code Content.parts} 的同级 part 会变成该 role 的独立输入，而不是这次
 * 函数调用的返回值。
 *
 * <p>Gemini Developer API（generativelanguage v1beta）的 {@code FunctionResponseBlob} 只声明 {@code
 * mimeType} 与 {@code data}，没有 {@code displayName}，因此编码器绝不伪造 {@code displayName} 或 {@code $ref}。
 */
final class GeminiContentEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** 工具结果可以内联的图片 MIME：只保留官方明确列举的保守集合，不扩大到全部 image/*。 */
  private static final Set<String> TOOL_RESULT_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");

  /** 工具结果唯一可以内联的文档 MIME。 */
  private static final String TOOL_RESULT_DOCUMENT_TYPE = "application/pdf";

  private GeminiContentEncoder() {}

  static void encodeUserBlock(ArrayNode parts, ProviderContentBlock block) {
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

  /**
   * 媒体来源必须是内联 Base64 data URI：percent/非 Base64 形态、非法 Base64 与空载荷都在编码期以 {@code INVALID_REQUEST}
   * 拒绝，绝不把无效 Base64 发往上游；http(s)/{@code gs} fileUri 仍按协议透传。
   */
  private static void encodeMediaBlock(ArrayNode parts, String mediaType, String source) {
    if (source == null || source.isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source URI must not be blank");
    }
    if (source.startsWith("data:")) {
      ProviderMediaValidator.Base64DataUri inline =
          ProviderMediaValidator.parseBase64DataUri(source);
      if (inline == null) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "media source must be a base64 data URI with a non-empty decodable payload");
      }
      ObjectNode part = parts.addObject();
      ObjectNode inlineData = part.putObject("inlineData");
      inlineData.put("mimeType", inline.mediaType());
      inlineData.put("data", inline.payload());
      return;
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
  static void encodeToolResultBlock(ArrayNode parts, ProviderToolResultBlock resultBlock) {
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
    if (!ProviderMediaValidator.isAllowedMediaType(mediaType, TOOL_RESULT_IMAGE_TYPES)
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
    ProviderMediaValidator.Base64DataUri inline = ProviderMediaValidator.parseBase64DataUri(source);
    if (inline != null && inline.mediaType().equalsIgnoreCase(mediaType)) {
      return inline.payload();
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
          JsonNode parsed =
              ProviderJsonHelper.parseStrictJson(jb.json(), "invalid JSON block in tool result");
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
            results.add(
                ProviderJsonHelper.parseStrictJson(jb.json(), "invalid JSON block in tool result"));
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
          JsonNode parsed =
              ProviderJsonHelper.parseStrictJson(jb.json(), "invalid JSON block in tool result");
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
            results.add(
                ProviderJsonHelper.parseStrictJson(jb.json(), "invalid JSON block in tool result"));
          }
        }
        wrapper.set("results", results);
      }
      return wrapper;
    }
  }
}
