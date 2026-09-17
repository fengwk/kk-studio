package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon wire {@code PROGRESS}/{@code COMPLETED} payload 的 capability-result codec。
 *
 * <p>wire shape:
 *
 * <ul>
 *   <li>{@code {"result":{"callId":string,"error":bool,"details":object,"contents":[...]}}}；
 *   <li>text: {@code {"type":"text","text":string}}；
 *   <li>json: {@code {"type":"json","json":value}}（value 原样编码）；
 *   <li>resource: {@code
 *       {"type":"resource","uploadId":uuid,"mediaType":string,"name":string|null,"size":long,
 *       "sha256":string,"preview":string?}} —— 纯元数据；{@code uploadId} 是 Platform 全局 Blob 上传行的 id。
 * </ul>
 *
 * <p><b>无二进制数据面：</b>wire 上永远不出现 resource bytes（既非 Base64 也非二进制帧）。Daemon 终态编码前先把 {@link
 * BinaryResultContent} 的字节经 {@link DaemonResourceUploader} 直传对象存储，因此 wire 只携带全局上传 id；接收方只做 id
 * 与尺寸校验，不解析、不下载、不持久化任何 daemon 本地地址。解码侧把该 id 还原为进程内的瞬态 {@link
 * ResourceRef#blobUploadUri(java.util.UUID)} 引用（{@code blob-upload:<uploadId>}），该 scheme 只在本进程内存在。
 *
 * <p>编码分相：{@link #encodeProgress} 只允许 text/json，任何 resource/binary 内容在任何上传之前拒绝；{@link
 * #encodeCompleted} 先完成全部计数与尺寸预算预检，预检全部通过后才开始上传，杜绝后置条目超限造成半途副作用。最终 payload 的 UTF-8 字节数必须 ≤ {@link
 * #MAX_PAYLOAD_UTF8_BYTES}（16 MiB），由 bounded 输出辅助在物化前中止；由于 resource 不再内联字节，该上限实际上 只约束 text/json
 * 与元数据。
 *
 * <p>解码侧：resource 还原为携带瞬态 {@code blob-upload:<uploadId>} 引用的 {@link ResourceResultContent}，并复核声明尺寸
 * 落在 {@code maximumResourceBytes} 聚合预算内。解码先施加原始 payload 的 UTF-8 上限，并配置 Jackson {@link
 * StreamReadConstraints} 限制字符串/嵌套/数字长度以防解析放大。
 */
public final class DaemonCapabilityResultCodec {

  /** 结果 payload 的原始 UTF-8 上限（解码前）与最终编码输出上限：16 MiB。 */
  public static final int MAX_PAYLOAD_UTF8_BYTES = 16 * 1024 * 1024;

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper(
          JsonFactory.builder()
              // 解析放大防护：字符串、嵌套深度、数字长度与文档长度全部有界（与 payload 上限同量级）。
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxStringLength(MAX_PAYLOAD_UTF8_BYTES)
                      .maxNestingDepth(100)
                      .maxNumberLength(1000)
                      .maxDocumentLength(MAX_PAYLOAD_UTF8_BYTES)
                      .build())
              .build());

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> RESOURCE_FIELDS =
      Set.of("type", "uploadId", "mediaType", "name", "size", "sha256", "preview");
  private static final UUID PREFLIGHT_UPLOAD_ID = new UUID(0L, 0L);
  private static final DaemonResourceUploader PREFLIGHT_UPLOADER =
      (invocationId, mediaType, name, bytes) ->
          new ResourceRef(
              ResourceRef.blobUploadUri(PREFLIGHT_UPLOAD_ID),
              mediaType,
              name,
              (long) bytes.length,
              "0".repeat(64));

  /**
   * 编码 PROGRESS 结果：只允许 text/json 内容；任何 {@link ResourceResultContent}/{@link BinaryResultContent}
   * 都在任何上传之前被拒绝。
   */
  public String encodeProgress(EnvironmentCapabilityResult partial) {
    Objects.requireNonNull(partial, "partial");
    for (ResultContent content : partial.contents()) {
      if (!(content instanceof TextResultContent) && !(content instanceof JsonResultContent)) {
        throw new DaemonProtocolException(
            "PROGRESS result must not contain " + content.getClass().getSimpleName() + " content");
      }
    }
    return writeBoundedPayload(buildResultTree(partial, null, null));
  }

  /**
   * 编码 COMPLETED 结果。
   *
   * <p>在第一个上传或字节读取之前完成全部预检：resource ref 必须携带非空 size/sha、binary 大小取自内容、单条与聚合资源字节都必须 ≤ {@code
   * maximumResourceBytes}。任何后置条目超限都不会产生任何上传副作用。
   *
   * @param maximumResourceBytes 本连接 WELCOME 通告的单条/聚合资源字节预算
   * @param uploader 把 binary 字节直传对象存储的端口；为 {@code null} 时 binary 内容确定性失败
   */
  public String encodeCompleted(
      EnvironmentCapabilityResult result,
      long maximumResourceBytes,
      DaemonResourceUploader uploader) {
    Objects.requireNonNull(result, "result");
    if (maximumResourceBytes <= 0) {
      throw new IllegalArgumentException("maximumResourceBytes must be positive");
    }
    long aggregate = 0;
    for (ResultContent content : result.contents()) {
      long size;
      if (content instanceof ResourceResultContent resource) {
        ResourceRef ref = resource.resource();
        if (ref.blobUploadId() == null) {
          throw new DaemonProtocolException(
              "resource must be uploaded to global storage before encoding");
        }
        if (ref.size() == null || ref.sha256() == null) {
          throw new DaemonProtocolException(
              "resource ref must declare size and sha256 for the daemon wire");
        }
        if (resource.textMetadata() != null) {
          throw new DaemonProtocolException(
              "daemon resource result must not declare text artifact metadata");
        }
        size = ref.size();
      } else if (content instanceof BinaryResultContent binary) {
        if (binary.textMetadata() != null) {
          throw new DaemonProtocolException(
              "daemon binary result must not declare text artifact metadata");
        }
        size = binary.size();
      } else {
        size = 0;
      }
      if (size > maximumResourceBytes - aggregate) {
        throw new DaemonProtocolException(
            "aggregate resource bytes exceed maximumResourceBytes: maximum="
                + maximumResourceBytes);
      }
      aggregate += size;
    }
    // 用固定长度 uploadId/sha 元数据构建等尺寸 wire 树，只计数不保留输出；完整 payload
    // 通过上限后才允许真实上传，避免后置 text/json 超限留下无消费者对象。
    ObjectNode preflight = buildResultTree(result, maximumResourceBytes, PREFLIGHT_UPLOADER);
    if (!BoundedJsonWriter.fits(preflight, MAX_PAYLOAD_UTF8_BYTES)) {
      throw payloadTooLarge();
    }
    return writeBoundedPayload(buildResultTree(result, maximumResourceBytes, uploader));
  }

  /**
   * 将 wire JSON 文本解码为 {@link EnvironmentCapabilityResult}；resource 还原为携带 {@code blob-upload:} 引用的
   * {@link ResourceResultContent}。
   */
  public EnvironmentCapabilityResult decodeResult(String payloadJson) {
    return decodeResult(payloadJson, null, Long.MAX_VALUE, true);
  }

  /**
   * 解码 PROGRESS 结果（专用入口：拒绝 resource）。
   *
   * @param expectedCallId 期望的调用 ID；空白或 null 时拒绝。
   * @param maximumResourceBytes 单条/聚合资源字节预算。
   */
  public EnvironmentCapabilityResult decodeProgressForInvocation(
      String payloadJson, String expectedCallId, long maximumResourceBytes) {
    return decodeResult(payloadJson, requireCallId(expectedCallId), maximumResourceBytes, false);
  }

  /**
   * 解码 COMPLETED 结果（专用入口：允许 resource 并还原为 {@code blob-upload:} 引用）。
   *
   * @param expectedCallId 期望的调用 ID；空白或 null 时拒绝。
   * @param maximumResourceBytes 单条/聚合资源字节预算。
   */
  public EnvironmentCapabilityResult decodeCompletedForInvocation(
      String payloadJson, String expectedCallId, long maximumResourceBytes) {
    return decodeResult(payloadJson, requireCallId(expectedCallId), maximumResourceBytes, true);
  }

  private static String requireCallId(String expectedCallId) {
    if (expectedCallId == null || expectedCallId.isBlank()) {
      throw new IllegalArgumentException("expectedCallId must not be blank");
    }
    return expectedCallId;
  }

  private EnvironmentCapabilityResult decodeResult(
      String payloadJson,
      String expectedCallId,
      long maximumResourceBytes,
      boolean allowResources) {
    if (maximumResourceBytes < 0) {
      throw new IllegalArgumentException("maximumResourceBytes must not be negative");
    }
    // 解析前先施加原始 payload 的 UTF-8 上限：不分配完整编码缓冲，超限即拒绝，避免解析放大。
    try {
      if (ResourceRef.utf8LengthUpTo(payloadJson, "payloadJson", MAX_PAYLOAD_UTF8_BYTES)
          > MAX_PAYLOAD_UTF8_BYTES) {
        throw new DaemonProtocolException(
            "payload exceeds " + MAX_PAYLOAD_UTF8_BYTES + " UTF-8 bytes");
      }
    } catch (DaemonProtocolException error) {
      throw error;
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("payload is not valid Unicode");
    }
    ObjectNode root = readRootObject(payloadJson, "result");
    rejectUnknownFields(root, Set.of("result"), "result");
    JsonNode resultNode = root.get("result");
    if (resultNode == null || resultNode.isNull()) {
      throw new DaemonProtocolException("payload must declare 'result'");
    }
    ObjectNode resultObject = requiredObject(resultNode, "result");
    rejectUnknownFields(resultObject, Set.of("callId", "error", "details", "contents"), "result");
    String callId = requiredText(resultObject, "callId", "result");
    if (expectedCallId != null && !expectedCallId.equals(callId)) {
      throw new DaemonProtocolException("result callId does not match expected invocationId");
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
    if (contentsNode.size() > EnvironmentCapabilityResult.MAX_CONTENT_ITEMS) {
      throw new DaemonProtocolException(
          "result 'contents' must not exceed "
              + EnvironmentCapabilityResult.MAX_CONTENT_ITEMS
              + " items");
    }
    List<ResultContent> contents = new ArrayList<>();
    long declaredResourceBytes = 0;
    int index = 0;
    for (JsonNode element : contentsNode) {
      // 先按声明尺寸扣减剩余聚合预算，再解析任何其它字段；超限条目在构造 ResourceRef 前即被拒绝。
      long remaining = maximumResourceBytes - declaredResourceBytes;
      DecodedContent decoded =
          readContent(element, "result.contents[" + index + "]", remaining, allowResources);
      declaredResourceBytes += decoded.resourceBytes;
      contents.add(decoded.content);
      index++;
    }
    try {
      return new EnvironmentCapabilityResult(callId, List.copyOf(contents), error, detailsJson);
    } catch (IllegalArgumentException invalid) {
      // EnvironmentCapabilityResult 构造期的有界输入校验（如 detailsJson 超限/非法 Unicode）按协议错误拒绝。
      throw new DaemonProtocolException("result fields are invalid", invalid);
    }
  }

  private ObjectNode buildResultTree(
      EnvironmentCapabilityResult result,
      Long maximumResourceBytes,
      DaemonResourceUploader uploader) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    ObjectNode wireResult = root.putObject("result");
    wireResult.put("callId", result.callId());
    wireResult.put("error", result.error());
    JsonNode details = readDetails(result.detailsJson());
    wireResult.set("details", details);
    ArrayNode contents = wireResult.putArray("contents");
    for (ResultContent content : result.contents()) {
      writeContent(contents, content, result.callId(), maximumResourceBytes, uploader);
    }
    return root;
  }

  /** 最终 payload 必须 ≤ {@link #MAX_PAYLOAD_UTF8_BYTES} UTF-8 字节：经 bounded 输出在物化前中止。 */
  private String writeBoundedPayload(ObjectNode root) {
    String payload = BoundedJsonWriter.write(root, MAX_PAYLOAD_UTF8_BYTES);
    if (payload == null) {
      throw payloadTooLarge();
    }
    return payload;
  }

  private DaemonProtocolException payloadTooLarge() {
    return new DaemonProtocolException(
        "daemon payload exceeds " + MAX_PAYLOAD_UTF8_BYTES + " UTF-8 bytes");
  }

  private void writeContent(
      ArrayNode contents,
      ResultContent content,
      String invocationId,
      Long maximumResourceBytes,
      DaemonResourceUploader uploader) {
    ObjectNode wireContent = contents.addObject();
    if (content instanceof TextResultContent text) {
      wireContent.put("type", "text");
      wireContent.put("text", text.text());
    } else if (content instanceof JsonResultContent json) {
      wireContent.put("type", "json");
      wireContent.set("json", readJson(json.json()));
    } else if (content instanceof BinaryResultContent binary) {
      // 字节永不进入 wire：先直传对象存储，再把返回的瞬时引用编码为纯元数据。
      ResourceRef uploaded =
          upload(invocationId, binary.mediaType(), null, binary.content(), uploader);
      writeResource(wireContent, uploadIdOf(uploaded), uploaded, null);
    } else if (content instanceof ResourceResultContent resource) {
      ResourceRef ref = resource.resource();
      // 只有已直传全局存储的上传引用可以进入终态：wire 只承载 uploadId，本地地址不是可跨节点取数地址。
      UUID uploadId = ref.blobUploadId();
      if (uploadId == null) {
        throw new DaemonProtocolException(
            "resource must be uploaded to global storage before encoding");
      }
      writeResource(wireContent, uploadId, ref, resource.preview());
    } else {
      throw new DaemonProtocolException("unsupported result content: " + content.getClass());
    }
  }

  private ResourceRef upload(
      String invocationId,
      String mediaType,
      String name,
      byte[] bytes,
      DaemonResourceUploader uploader) {
    if (uploader == null) {
      throw new DaemonProtocolException("resource upload is not configured");
    }
    try {
      return uploader.upload(invocationId, mediaType, name, bytes);
    } catch (IOException error) {
      throw new DaemonProtocolException("cannot upload resource content");
    } catch (RuntimeException error) {
      if (error instanceof DaemonProtocolException protocol) {
        throw protocol;
      }
      throw new DaemonProtocolException("cannot upload resource content");
    }
  }

  private DecodedContent readContent(
      JsonNode node, String context, long remainingResourceBytes, boolean allowResources) {
    ObjectNode obj = requiredObject(node, context);
    String type = requiredText(obj, "type", context);
    return switch (type) {
      case "text" -> {
        rejectUnknownFields(obj, Set.of("type", "text"), context);
        // 空字符串是合法文本内容；只要求类型是 string。
        String text = requiredString(obj, "text", context);
        yield new DecodedContent(new TextResultContent(text), 0);
      }
      case "json" -> {
        rejectUnknownFields(obj, Set.of("type", "json"), context);
        JsonNode value = obj.get("json");
        if (value == null) {
          throw new DaemonProtocolException(context + " must declare 'json'");
        }
        try {
          // JsonResultContent 构造期的 1 MiB 上限校验：超限/非法 Unicode 按协议错误拒绝。
          yield new DecodedContent(new JsonResultContent(writeJson(value)), 0);
        } catch (IllegalArgumentException error) {
          throw new DaemonProtocolException(context + " 'json' is invalid or too large");
        }
      }
      case "resource" -> readResource(obj, context, remainingResourceBytes, allowResources);
      default -> throw new DaemonProtocolException(context + " unknown content type: " + type);
    };
  }

  private DecodedContent readResource(
      ObjectNode obj, String context, long remainingResourceBytes, boolean allowResources) {
    if (!allowResources) {
      throw new DaemonProtocolException("PROGRESS result must not contain resource content");
    }
    rejectUnknownFields(obj, RESOURCE_FIELDS, context);
    UUID uploadId = requiredUuid(obj, "uploadId", context);
    String mediaType = requiredText(obj, "mediaType", context);
    String name = optionalTextOrNull(obj, "name", context);
    // size/sha256 对每个 wire resource 都是必填：缺失或 null 直接拒绝，杜绝声明为 null 绕过大小预检。
    long size = requiredLong(obj, "size", context);
    String sha256 = requiredText(obj, "sha256", context);
    String preview = readPreview(obj, context);
    // 聚合预检先于任何引用解析：预算耗尽必须报聚合错误，而不是更晚的字段错误。
    if (size > remainingResourceBytes) {
      throw new DaemonProtocolException(
          "aggregate declared resource bytes exceed maximumResourceBytes: declared="
              + size
              + " remaining="
              + remainingResourceBytes);
    }
    ResourceRef ref;
    try {
      ref = new ResourceRef(ResourceRef.blobUploadUri(uploadId), mediaType, name, size, sha256);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(context + " resource fields are invalid");
    }
    // wire 只承载元数据：解码结果保留进程内的瞬态上传引用，字节由消费方通过全局上传契约原子转移。
    return new DecodedContent(new ResourceResultContent(ref, preview, null), size);
  }

  private String readPreview(ObjectNode obj, String context) {
    JsonNode previewNode = obj.get("preview");
    if (previewNode == null || previewNode.isNull()) {
      return null;
    }
    if (!previewNode.isTextual()) {
      throw new DaemonProtocolException(context + " 'preview' must be a string or null");
    }
    String preview = previewNode.textValue();
    if (ResourceRef.utf8LengthUpTo(preview, "preview", ResourceRef.MAX_PREVIEW_UTF8_BYTES)
        > ResourceRef.MAX_PREVIEW_UTF8_BYTES) {
      throw new DaemonProtocolException(
          context + " 'preview' exceeds " + ResourceRef.MAX_PREVIEW_UTF8_BYTES + " UTF-8 bytes");
    }
    return preview;
  }

  private void writeResource(
      ObjectNode wireContent, UUID uploadId, ResourceRef ref, String preview) {
    if (uploadId == null || ref.size() == null || ref.sha256() == null) {
      throw new DaemonProtocolException(
          "resource ref must declare an upload id and size/sha256 for the daemon wire");
    }
    wireContent.put("type", "resource");
    wireContent.put("uploadId", uploadId.toString());
    wireContent.put("mediaType", ref.mediaType());
    putNullableText(wireContent, "name", ref.name());
    wireContent.put("size", ref.size());
    wireContent.put("sha256", ref.sha256());
    if (preview != null) {
      wireContent.put("preview", preview);
    }
  }

  private JsonNode readDetails(String detailsJson) {
    // EnvironmentCapabilityResult 构造期已保证 detailsJson 是合法 JSON object；codec 不做规范化，若触达非法值直接严格失败。
    if (detailsJson == null || detailsJson.isBlank()) {
      throw new DaemonProtocolException("result 'details' must be a JSON object");
    }
    try {
      JsonNode value = OBJECT_MAPPER.readTree(detailsJson);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException("result 'details' must be a JSON object");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("result 'details' must be valid JSON");
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
      throw new DaemonProtocolException("json must be valid");
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
      throw new DaemonProtocolException(name + " must be valid JSON");
    }
  }

  private String writeJson(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon payload");
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

  private String optionalTextOrNull(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a string or null");
    }
    return value.textValue();
  }

  private long requiredLong(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null || value.isNull()) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a long integer");
    }
    long result = value.longValue();
    if (result < 0) {
      throw new DaemonProtocolException(context + " '" + field + "' must not be negative");
    }
    return result;
  }

  /** 严格读取一个规范小写 UUID 字符串字段。 */
  private UUID requiredUuid(ObjectNode obj, String field, String context) {
    String text = requiredText(obj, field, context);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw new DaemonProtocolException(context + " '" + field + "' must be a canonical UUID");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a canonical UUID");
    }
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

  private static void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  /** 从瞬态 {@code blob-upload:<uploadId>} 引用中取出 uploadId；非该 scheme 时返回 null。 */
  public static UUID uploadIdOf(ResourceRef ref) {
    Objects.requireNonNull(ref, "ref");
    return ref.blobUploadId();
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

  private record DecodedContent(ResultContent content, long resourceBytes) {}
}
