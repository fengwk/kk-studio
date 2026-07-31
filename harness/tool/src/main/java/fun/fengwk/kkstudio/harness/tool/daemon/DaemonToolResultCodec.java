package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon v1 {@code PARTIAL}/{@code COMPLETED} payload 的 tool-result codec。
 *
 * <p>wire shape:
 *
 * <ul>
 *   <li>{@code {"result":{"toolCallId":string,"error":bool,"details":object,"contents":[...]}}}；
 *   <li>text: {@code {"type":"text","text":string}}；
 *   <li>json: {@code {"type":"json","json":value}}（value 原样编码）；
 *   <li>artifact: {@code
 *       {"type":"artifact","artifactId":string,"mediaType":string,"sizeBytes":long,
 *       "contentBase64":string}}；{@code contentBase64} 是 artifact 原始 bytes 的 RFC 4648 basic
 *       Base64（无换行）。
 * </ul>
 *
 * <p>解码将 artifact 还原为内联 {@link BinaryToolContent}，不在 codec 边界持久化 ArtifactStore。PARTIAL 应在调用侧拒绝
 * artifact（见 {@link #decodeResult(String, long, boolean)}）。编码时 {@link ArtifactToolContent} 通过
 * {@link DaemonArtifactContentWriter} 读取本地 bytes；{@link BinaryToolContent} 直接写出。
 */
public final class DaemonToolResultCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * 将 {@link ToolResult} 编码为 v1 wire JSON 文本。
   *
   * @param result 待编码的 ToolResult；不得为 null。
   * @param artifactWriter artifact 字节读取 SPI；当 {@code result.contents()} 包含 {@link
   *     ArtifactToolContent} 时必须提供。
   * @return 完整 wire JSON 文本（始终包含顶层 {@code result} 对象）。
   * @throws DaemonProtocolException 当 writer 抛 {@link IOException} 或结果包含不支持的内容类型时。
   */
  public String encodeResult(ToolResult result, DaemonArtifactContentWriter artifactWriter) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(artifactWriter, "artifactWriter");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    ObjectNode wireResult = root.putObject("result");
    wireResult.put("toolCallId", result.toolCallId());
    wireResult.put("error", result.error());
    JsonNode details = readDetails(result.detailsJson());
    wireResult.set("details", details);
    ArrayNode contents = wireResult.putArray("contents");
    for (ToolContent content : result.contents()) {
      writeContent(contents, content, artifactWriter);
    }
    return writeJson(root);
  }

  /** 将 v1 wire JSON 文本解码为 {@link ToolResult}；artifact 还原为内联 {@link BinaryToolContent}。 */
  public ToolResult decodeResult(String payloadJson) {
    return decodeResult(payloadJson, Long.MAX_VALUE, true);
  }

  /**
   * 将 v1 wire JSON 文本解码为 {@link ToolResult}，并在 Base64 分配前限制单个 artifact 的声明字节数。
   *
   * @param allowArtifacts false 时拒绝 artifact 内容（PARTIAL 使用）。
   */
  public ToolResult decodeResult(
      String payloadJson, long maximumArtifactBytes, boolean allowArtifacts) {
    return decodeResult(payloadJson, null, maximumArtifactBytes, allowArtifacts);
  }

  /**
   * Decodes a result for one expected daemon invocation. Artifacts stay inline as {@link
   * BinaryToolContent}; no durable store is touched.
   */
  public ToolResult decodeResultForInvocation(
      String payloadJson,
      String expectedToolCallId,
      long maximumArtifactBytes,
      boolean allowArtifacts) {
    if (expectedToolCallId == null || expectedToolCallId.isBlank()) {
      throw new IllegalArgumentException("expectedToolCallId must not be blank");
    }
    return decodeResult(payloadJson, expectedToolCallId, maximumArtifactBytes, allowArtifacts);
  }

  private ToolResult decodeResult(
      String payloadJson,
      String expectedToolCallId,
      long maximumArtifactBytes,
      boolean allowArtifacts) {
    if (maximumArtifactBytes < 0) {
      throw new IllegalArgumentException("maximumArtifactBytes must not be negative");
    }
    ObjectNode root = readRootObject(payloadJson, "result");
    Set<String> allowedTop = Set.of("result");
    rejectUnknownFields(root, allowedTop, "result");
    JsonNode resultNode = root.get("result");
    if (resultNode == null || resultNode.isNull()) {
      throw new DaemonProtocolException("payload must declare 'result'");
    }
    ObjectNode resultObject = requiredObject(resultNode, "result");
    Set<String> allowedResult = Set.of("toolCallId", "error", "details", "contents");
    rejectUnknownFields(resultObject, allowedResult, "result");
    String toolCallId = requiredText(resultObject, "toolCallId", "result");
    if (expectedToolCallId != null && !expectedToolCallId.equals(toolCallId)) {
      throw new DaemonProtocolException("result toolCallId does not match expected invocationId");
    }
    boolean error = requiredBoolean(resultObject, "error", "result");
    JsonNode detailsNode = resultObject.get("details");
    if (detailsNode == null || detailsNode.isNull()) {
      throw new DaemonProtocolException("result must declare 'details'");
    }
    if (!detailsNode.isObject()) {
      throw new DaemonProtocolException("result 'details' must be a JSON object");
    }
    String detailsJson = writeJson(detailsNode);
    JsonNode contentsNode = resultObject.get("contents");
    if (contentsNode == null || contentsNode.isNull()) {
      throw new DaemonProtocolException("result must declare 'contents'");
    }
    if (!contentsNode.isArray()) {
      throw new DaemonProtocolException("result 'contents' must be an array");
    }
    List<ToolContent> contents = new ArrayList<>();
    int index = 0;
    for (JsonNode element : contentsNode) {
      contents.add(
          readContent(
              element, "result.contents[" + index + "]", maximumArtifactBytes, allowArtifacts));
      index++;
    }
    return new ToolResult(toolCallId, List.copyOf(contents), error, detailsJson, false);
  }

  private void writeContent(
      ArrayNode contents, ToolContent content, DaemonArtifactContentWriter artifactWriter) {
    ObjectNode wireContent = contents.addObject();
    if (content instanceof TextToolContent text) {
      wireContent.put("type", "text");
      wireContent.put("text", text.text());
    } else if (content instanceof JsonToolContent json) {
      wireContent.put("type", "json");
      wireContent.set("json", readJson(json.json()));
    } else if (content instanceof BinaryToolContent binary) {
      byte[] bytes = binary.content();
      wireContent.put("type", "artifact");
      wireContent.put("artifactId", "inline");
      wireContent.put("mediaType", binary.mediaType());
      wireContent.put("sizeBytes", bytes.length);
      wireContent.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
    } else if (content instanceof ArtifactToolContent artifact) {
      ArtifactRef ref = artifact.artifact();
      byte[] bytes;
      try {
        bytes = artifactWriter.readBytes(ref);
      } catch (IOException error) {
        throw new DaemonProtocolException(
            "cannot read artifact bytes for " + ref.artifactId(), error);
      }
      if (bytes == null) {
        throw new DaemonProtocolException(
            "artifact writer returned null bytes for " + ref.artifactId());
      }
      if (bytes.length != ref.sizeBytes()) {
        throw new DaemonProtocolException(
            "artifact writer size mismatch for "
                + ref.artifactId()
                + ": declared="
                + ref.sizeBytes()
                + " actual="
                + bytes.length);
      }
      wireContent.put("type", "artifact");
      wireContent.put("artifactId", ref.artifactId());
      wireContent.put("mediaType", ref.mediaType());
      wireContent.put("sizeBytes", ref.sizeBytes());
      wireContent.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
    } else {
      throw new DaemonProtocolException("unsupported tool content: " + content.getClass());
    }
  }

  private ToolContent readContent(
      JsonNode node, String context, long maximumArtifactBytes, boolean allowArtifacts) {
    ObjectNode obj = requiredObject(node, context);
    String type = requiredText(obj, "type", context);
    switch (type) {
      case "text" -> {
        rejectUnknownFields(obj, Set.of("type", "text"), context);
        String text = requiredText(obj, "text", context);
        return new TextToolContent(text);
      }
      case "json" -> {
        rejectUnknownFields(obj, Set.of("type", "json"), context);
        JsonNode value = obj.get("json");
        if (value == null) {
          throw new DaemonProtocolException(context + " must declare 'json'");
        }
        return new JsonToolContent(writeJson(value));
      }
      case "artifact" -> {
        if (!allowArtifacts) {
          throw new DaemonProtocolException("PARTIAL result must not contain artifact content");
        }
        Set<String> allowedArtifact =
            Set.of("type", "artifactId", "mediaType", "sizeBytes", "contentBase64");
        rejectUnknownFields(obj, allowedArtifact, context);
        requiredText(obj, "artifactId", context);
        String mediaType = requiredText(obj, "mediaType", context);
        long sizeBytes = requiredLong(obj, "sizeBytes", context);
        if (sizeBytes < 0) {
          throw new DaemonProtocolException(context + " 'sizeBytes' must not be negative");
        }
        if (sizeBytes > maximumArtifactBytes) {
          throw new DaemonProtocolException(
              context
                  + " 'sizeBytes' exceeds maximumArtifactBytes: declared="
                  + sizeBytes
                  + " maximum="
                  + maximumArtifactBytes);
        }
        String contentBase64 = requiredString(obj, "contentBase64", context);
        if ((long) contentBase64.length() != canonicalBase64Length(sizeBytes)) {
          throw new DaemonProtocolException(
              context
                  + " 'contentBase64' must use canonical Base64 with encoded length matching "
                  + "'sizeBytes'");
        }
        byte[] bytes;
        try {
          bytes = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException error) {
          throw new DaemonProtocolException(
              context + " 'contentBase64' is not valid Base64", error);
        }
        if (bytes.length != sizeBytes) {
          throw new DaemonProtocolException(
              context
                  + " 'contentBase64' decoded length does not match 'sizeBytes': declared="
                  + sizeBytes
                  + " actual="
                  + bytes.length);
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(contentBase64)) {
          throw new DaemonProtocolException(context + " 'contentBase64' must use canonical Base64");
        }
        return new BinaryToolContent(mediaType, bytes);
      }
      default -> throw new DaemonProtocolException(context + " unknown content type: " + type);
    }
  }

  private JsonNode readDetails(String detailsJson) {
    try {
      JsonNode value =
          OBJECT_MAPPER.readTree(detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException("result 'details' must be a JSON object");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("result 'details' must be valid JSON", error);
    }
  }

  private JsonNode readJson(String json) {
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null) {
        throw new DaemonProtocolException("json must not be null");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("json must be valid", error);
    }
  }

  private ObjectNode readRootObject(String json, String name) {
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException(name + " must be a JSON object");
      }
      return (ObjectNode) value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException(name + " must be valid JSON", error);
    }
  }

  private String writeJson(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon payload", error);
    }
  }

  private ObjectNode requiredObject(JsonNode node, String context) {
    if (node == null || node.isNull()) {
      throw new DaemonProtocolException(context + " must be a JSON object");
    }
    if (!node.isObject()) {
      throw new DaemonProtocolException(
          context
              + " must be a JSON object but was "
              + node.getNodeType().name().toLowerCase(Locale.ROOT));
    }
    return (ObjectNode) node;
  }

  private String requiredText(ObjectNode obj, String field, String context) {
    String text = requiredString(obj, field, context);
    if (text == null || text.isBlank()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be non-blank");
    }
    return text;
  }

  private String requiredString(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null || value.isNull()) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (!value.isTextual()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a string");
    }
    return value.textValue();
  }

  private boolean requiredBoolean(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null || value.isNull()) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (!value.isBoolean()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a boolean");
    }
    return value.booleanValue();
  }

  private long requiredLong(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null || value.isNull()) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a long integer");
    }
    return value.longValue();
  }

  private long canonicalBase64Length(long sizeBytes) {
    long groups = sizeBytes / 3;
    if (sizeBytes % 3 != 0) {
      groups++;
    }
    return groups > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : groups * 4;
  }

  private void rejectUnknownFields(ObjectNode obj, Set<String> allowed, String context) {
    Iterator<String> fields = obj.fieldNames();
    if (fields == null) {
      return;
    }
    while (fields.hasNext()) {
      String name = fields.next();
      if (!allowed.contains(name)) {
        throw new DaemonProtocolException(context + " unknown field: '" + name + "'");
      }
    }
  }
}
