package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
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
 * <p>codec 在边界拒绝：非对象 / 未知 / 缺失 / 多余字段；非 string 的 text / artifactId / mediaType；非 long 的 sizeBytes；非
 * canonical Base64 或解码后长度不匹配 sizeBytes；非对象的 details；非数组的 contents；非允许 type 字符串。JSON content 可以是任意
 * JSON 值。错误均以 {@link DaemonProtocolException} 抛出。
 *
 * <p>{@link #encodeResult(ToolResult, DaemonArtifactContentWriter)} 通过 {@link
 * DaemonArtifactContentWriter} 从 Daemon 本地 artifact sink 读取 bytes；{@link #decodeResult(String,
 * DaemonArtifactContentReader)} 通过 {@link DaemonArtifactContentReader} 在接收端持久化并返回全局 ref。codec 不依赖
 * {@code harness/daemon}，由 daemon / future gateway 各自提供 SPI 实现。
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

  /**
   * 将 v1 wire JSON 文本解码为 {@link ToolResult}；artifact 内容会通过 {@link DaemonArtifactContentReader}
   * 持久化并返回新的全局 ref。
   *
   * @param payloadJson wire JSON 文本。
   * @param artifactReader artifact 持久化 SPI。
   * @return 还原后的 {@link ToolResult}；{@link ArtifactToolContent} 使用 reader 返回的全局 ref。
   * @throws DaemonProtocolException 任何协议层错误。
   */
  public ToolResult decodeResult(String payloadJson, DaemonArtifactContentReader artifactReader) {
    Objects.requireNonNull(artifactReader, "artifactReader");
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
      contents.add(readContent(element, "result.contents[" + index + "]", artifactReader));
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
      JsonNode node, String context, DaemonArtifactContentReader artifactReader) {
    ObjectNode obj = requiredObject(node, context);
    Set<String> allowed = Set.of("type", "text");
    String type = requiredText(obj, "type", context);
    switch (type) {
      case "text" -> {
        rejectUnknownFields(obj, allowed, context);
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
        Set<String> allowedArtifact =
            Set.of("type", "artifactId", "mediaType", "sizeBytes", "contentBase64");
        rejectUnknownFields(obj, allowedArtifact, context);
        String artifactId = requiredText(obj, "artifactId", context);
        String mediaType = requiredText(obj, "mediaType", context);
        long sizeBytes = requiredLong(obj, "sizeBytes", context);
        if (sizeBytes < 0) {
          throw new DaemonProtocolException(context + " 'sizeBytes' must not be negative");
        }
        String contentBase64 = requiredString(obj, "contentBase64", context);
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
        ArtifactRef ref = artifactReader.store(mediaType, sizeBytes, bytes);
        if (ref == null) {
          throw new DaemonProtocolException(context + " artifact reader returned null ref");
        }
        if (!mediaType.equals(ref.mediaType())) {
          throw new DaemonProtocolException(
              context
                  + " artifact reader returned mismatched mediaType: wire="
                  + mediaType
                  + " ref="
                  + ref.mediaType());
        }
        if (ref.sizeBytes() != sizeBytes) {
          throw new DaemonProtocolException(
              context
                  + " artifact reader returned mismatched sizeBytes: wire="
                  + sizeBytes
                  + " ref="
                  + ref.sizeBytes());
        }
        return new ArtifactToolContent(ref);
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
          context + " must be a JSON object but was " + node.getNodeType().name().toLowerCase());
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
