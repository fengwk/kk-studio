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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon wire {@code PARTIAL}/{@code COMPLETED} payload 的 capability-result codec。
 *
 * <p>wire shape:
 *
 * <ul>
 *   <li>{@code {"result":{"callId":string,"error":bool,"details":object,"contents":[...]}}}；
 *   <li>text: {@code {"type":"text","text":string}}；
 *   <li>json: {@code {"type":"json","json":value}}（value 原样编码）；
 *   <li>resource: {@code {"type":"resource","uri":string,"mediaType":string,"name":string|null,
 *       "size":long,"sha256":string,"contentBase64":string}}；{@code size}/{@code sha256} 必填且先于任何
 *       Base64 分配完成校验；{@code contentBase64} 是 resource 原始 bytes 的 RFC 4648 basic Base64（无换行），
 *       且解码后长度必须等于声明 {@code size}、摘要必须等于声明 {@code sha256}。
 * </ul>
 *
 * <p>编码分相：{@link #encodePartial} 只允许 text/json，任何 resource/binary 内容在任何 store 操作之前拒绝； {@link
 * #encodeCompleted} 先对全部内容做计数/单条/聚合资源字节预算预检（默认 {@link #DEFAULT_MAX_RESOURCE_BYTES} 8
 * MiB），预检全部通过后才允许任何 store 读写，杜绝后置条目超限造成半途副作用。最终 payload 的 UTF-8 字节数必须 ≤ {@link
 * #MAX_PAYLOAD_UTF8_BYTES}（16 MiB），由 bounded 输出辅助在物化前中止。
 *
 * <p>解码侧：{@link ResourceResultContent} 通过 {@link DaemonResourceStore#read} 读取字节并复核 size/sha； {@link
 * BinaryResultContent} 先经 {@link DaemonResourceStore#store} 落盘再编码返回的 resource。解码将 resource 还原为内联
 * {@link BinaryResultContent}，不在 codec 边界持久化（入站 daemon URI 永远不是 durable 目的地）。解码先施加原始 payload 的
 * UTF-8 上限（{@link #MAX_PAYLOAD_UTF8_BYTES}），再在 Base64 分配前按“当前 size 是否超过剩余聚合预算”拒绝超限条目， 并配置 Jackson
 * {@link StreamReadConstraints} 限制字符串/嵌套/数字长度以防解析放大。
 */
public final class DaemonCapabilityResultCodec {

  /** 默认单资源/聚合资源字节预算：8 MiB（Base64 后约 10.7 MiB 字符，适配 gateway 默认 16 MiB 入站文本上限）。 */
  public static final long DEFAULT_MAX_RESOURCE_BYTES = 8L * 1024 * 1024;

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
      Set.of("type", "uri", "mediaType", "name", "size", "sha256", "contentBase64");

  /**
   * 编码 PARTIAL 结果：只允许 text/json 内容；任何 {@link ResourceResultContent}/{@link BinaryResultContent} 都在
   * 任何 store 操作之前被拒绝。
   */
  public String encodePartial(
      EnvironmentCapabilityResult partial, DaemonResourceStore resourceStore) {
    Objects.requireNonNull(partial, "partial");
    Objects.requireNonNull(resourceStore, "resourceStore");
    for (ResultContent content : partial.contents()) {
      if (!(content instanceof TextResultContent) && !(content instanceof JsonResultContent)) {
        throw new DaemonProtocolException(
            "PARTIAL result must not contain " + content.getClass().getSimpleName() + " content");
      }
    }
    return writeBoundedPayload(buildResultTree(partial, resourceStore));
  }

  /** 编码 COMPLETED 结果，使用默认资源字节预算 {@link #DEFAULT_MAX_RESOURCE_BYTES}。 */
  public String encodeCompleted(
      EnvironmentCapabilityResult result, DaemonResourceStore resourceStore) {
    return encodeCompleted(result, DEFAULT_MAX_RESOURCE_BYTES, resourceStore);
  }

  /**
   * 编码 COMPLETED 结果。
   *
   * <p>在第一个 {@code store.store}/{@code store.read}、Base64 与输出树构建之前完成全部预检：内容数 ≤ {@link
   * EnvironmentCapabilityResult#MAX_CONTENT_ITEMS}（由 EnvironmentCapabilityResult 构造期强制，codec
   * 不再重复）；resource ref 必须携带非空 size/sha； binary 大小取自内容；单条与聚合资源字节都必须 ≤ {@code
   * maximumResourceBytes}。任何后置条目超限都不会产生任何 store 副作用。 最终 payload 必须 ≤ {@link
   * #MAX_PAYLOAD_UTF8_BYTES} UTF-8 字节。
   */
  public String encodeCompleted(
      EnvironmentCapabilityResult result,
      long maximumResourceBytes,
      DaemonResourceStore resourceStore) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(resourceStore, "resourceStore");
    if (maximumResourceBytes <= 0) {
      throw new IllegalArgumentException("maximumResourceBytes must be positive");
    }
    long aggregate = 0;
    for (ResultContent content : result.contents()) {
      long size;
      if (content instanceof ResourceResultContent resource) {
        ResourceRef ref = resource.resource();
        if (ref.size() == null || ref.sha256() == null) {
          throw new DaemonProtocolException(
              "resource ref must declare size and sha256 for the daemon wire: " + ref.uri());
        }
        size = ref.size();
      } else if (content instanceof BinaryResultContent binary) {
        size = binary.content().length;
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
    return writeBoundedPayload(buildResultTree(result, resourceStore));
  }

  /**
   * 将 wire JSON 文本解码为 {@link EnvironmentCapabilityResult}；resource 还原为内联 {@link
   * BinaryResultContent}。
   */
  public EnvironmentCapabilityResult decodeResult(String payloadJson) {
    return decodeResult(payloadJson, null, DEFAULT_MAX_RESOURCE_BYTES, true);
  }

  /**
   * 解码 PARTIAL 结果（专用入口：拒绝 resource）。
   *
   * @param expectedCallId 期望的调用 ID；空白或 null 时拒绝。
   * @param maximumResourceBytes 单条/聚合资源字节预算。
   */
  public EnvironmentCapabilityResult decodePartialForInvocation(
      String payloadJson, String expectedCallId, long maximumResourceBytes) {
    return decodeResult(payloadJson, requireCallId(expectedCallId), maximumResourceBytes, false);
  }

  /**
   * 解码 COMPLETED 结果（专用入口：允许 resource 并还原为内联 {@link BinaryResultContent}）。
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
      throw new DaemonProtocolException("payload is not valid Unicode", error);
    }
    ObjectNode root = readRootObject(payloadJson, "result");
    Set<String> allowedTop = Set.of("result");
    rejectUnknownFields(root, allowedTop, "result");
    JsonNode resultNode = root.get("result");
    if (resultNode == null || resultNode.isNull()) {
      throw new DaemonProtocolException("payload must declare 'result'");
    }
    ObjectNode resultObject = requiredObject(resultNode, "result");
    Set<String> allowedResult = Set.of("callId", "error", "details", "contents");
    rejectUnknownFields(resultObject, allowedResult, "result");
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
    long decodedResourceBytes = 0;
    int index = 0;
    for (JsonNode element : contentsNode) {
      // 剩余聚合预算在 readContent 内先于任何 Base64 分配完成预检；预检保证累加不溢出。
      DecodedContent decoded =
          readContent(
              element,
              "result.contents[" + index + "]",
              maximumResourceBytes - decodedResourceBytes,
              allowResources);
      decodedResourceBytes += decoded.resourceBytes;
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
      EnvironmentCapabilityResult result, DaemonResourceStore resourceStore) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    ObjectNode wireResult = root.putObject("result");
    wireResult.put("callId", result.callId());
    wireResult.put("error", result.error());
    JsonNode details = readDetails(result.detailsJson());
    wireResult.set("details", details);
    ArrayNode contents = wireResult.putArray("contents");
    for (ResultContent content : result.contents()) {
      writeContent(contents, content, resourceStore);
    }
    return root;
  }

  /** 最终 payload 必须 ≤ {@link #MAX_PAYLOAD_UTF8_BYTES} UTF-8 字节：经 bounded 输出在物化前中止。 */
  private String writeBoundedPayload(ObjectNode root) {
    String payload = BoundedJsonWriter.write(root, MAX_PAYLOAD_UTF8_BYTES);
    if (payload == null) {
      throw new DaemonProtocolException(
          "daemon payload exceeds " + MAX_PAYLOAD_UTF8_BYTES + " UTF-8 bytes");
    }
    return payload;
  }

  private void writeContent(ArrayNode contents, ResultContent content, DaemonResourceStore store) {
    ObjectNode wireContent = contents.addObject();
    if (content instanceof TextResultContent text) {
      wireContent.put("type", "text");
      wireContent.put("text", text.text());
    } else if (content instanceof JsonResultContent json) {
      wireContent.put("type", "json");
      wireContent.set("json", readJson(json.json()));
    } else if (content instanceof ResourceResultContent resource) {
      ResourceRef ref = resource.resource();
      DaemonResourceRef dRef =
          new DaemonResourceRef(ref.uri(), ref.mediaType(), ref.name(), ref.size(), ref.sha256());
      byte[] bytes = readAndVerify(store, dRef, ref);
      writeResource(wireContent, ref, bytes);
    } else if (content instanceof BinaryResultContent binary) {
      ResourceRef ref;
      try {
        DaemonResourceRef stored = store.store(binary.content(), binary.mediaType());
        ref =
            new ResourceRef(
                stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
      } catch (IOException error) {
        throw new DaemonProtocolException("cannot store binary result content", error);
      }
      writeResource(wireContent, ref, binary.content());
    } else {
      throw new DaemonProtocolException("unsupported result content: " + content.getClass());
    }
  }

  private DecodedContent readContent(
      JsonNode node, String context, long remainingResourceBytes, boolean allowResources) {
    ObjectNode obj = requiredObject(node, context);
    String type = requiredText(obj, "type", context);
    switch (type) {
      case "text" -> {
        rejectUnknownFields(obj, Set.of("type", "text"), context);
        // 空字符串是合法文本内容；只要求类型是 string。
        String text = requiredString(obj, "text", context);
        return new DecodedContent(new TextResultContent(text), 0);
      }
      case "json" -> {
        rejectUnknownFields(obj, Set.of("type", "json"), context);
        JsonNode value = obj.get("json");
        if (value == null) {
          throw new DaemonProtocolException(context + " must declare 'json'");
        }
        try {
          // JsonResultContent 构造期的 1 MiB 上限校验：超限/非法 Unicode 按协议错误拒绝。
          return new DecodedContent(new JsonResultContent(writeJson(value)), 0);
        } catch (IllegalArgumentException error) {
          throw new DaemonProtocolException(context + " 'json' is invalid or too large", error);
        }
      }
      case "resource" -> {
        if (!allowResources) {
          throw new DaemonProtocolException("PARTIAL result must not contain resource content");
        }
        rejectUnknownFields(obj, RESOURCE_FIELDS, context);
        String uri = requiredText(obj, "uri", context);
        String mediaType = requiredText(obj, "mediaType", context);
        String name = optionalTextOrNull(obj, "name", context);
        // size/sha256 对每个 wire resource 都是必填：缺失或 null 直接拒绝，杜绝声明为 null 绕过大小预检。
        long size = requiredLong(obj, "size", context);
        String sha256 = requiredText(obj, "sha256", context);
        String contentBase64 = requiredString(obj, "contentBase64", context);
        // 先完成全量字段/URI 校验，再进行任何 Base64 分配。
        try {
          new ResourceRef(uri, mediaType, name, size, sha256);
        } catch (IllegalArgumentException error) {
          throw new DaemonProtocolException(context + " resource fields are invalid", error);
        }
        // 聚合预检先于 Base64 校验/分配：即使 contentBase64 非法，预算耗尽也必须报聚合错误。
        if (size > remainingResourceBytes) {
          throw new DaemonProtocolException(
              "aggregate decoded resource bytes exceed maximumResourceBytes: declared="
                  + size
                  + " remaining="
                  + remainingResourceBytes);
        }
        if (contentBase64.length() != canonicalBase64Length(size)) {
          throw new DaemonProtocolException(
              context
                  + " 'contentBase64' must use canonical Base64 with encoded length matching "
                  + "'size'");
        }
        byte[] bytes;
        try {
          bytes = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException error) {
          throw new DaemonProtocolException(
              context + " 'contentBase64' is not valid Base64", error);
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(contentBase64)) {
          throw new DaemonProtocolException(context + " 'contentBase64' must use canonical Base64");
        }
        if (bytes.length != size) {
          throw new DaemonProtocolException(
              context
                  + " 'contentBase64' decoded length does not match 'size': declared="
                  + size
                  + " actual="
                  + bytes.length);
        }
        if (!sha256.equals(sha256Hex(bytes))) {
          throw new DaemonProtocolException(
              context + " 'sha256' does not match decoded 'contentBase64' bytes");
        }
        return new DecodedContent(new BinaryResultContent(mediaType, bytes), bytes.length);
      }
      default -> throw new DaemonProtocolException(context + " unknown content type: " + type);
    }
  }

  private static byte[] readAndVerify(
      DaemonResourceStore store, DaemonResourceRef dRef, ResourceRef ref) {
    byte[] bytes;
    try {
      bytes = store.read(dRef);
    } catch (IOException error) {
      throw new DaemonProtocolException("cannot read resource bytes for " + ref.uri(), error);
    }
    if (bytes == null) {
      throw new DaemonProtocolException("resource store returned null bytes for " + ref.uri());
    }
    if (ref.size() != null && bytes.length != ref.size()) {
      throw new DaemonProtocolException(
          "resource store size mismatch for "
              + ref.uri()
              + ": declared="
              + ref.size()
              + " actual="
              + bytes.length);
    }
    if (ref.sha256() != null && !ref.sha256().equals(sha256Hex(bytes))) {
      throw new DaemonProtocolException("resource store sha256 mismatch for " + ref.uri());
    }
    return bytes;
  }

  private void writeResource(ObjectNode wireContent, ResourceRef ref, byte[] bytes) {
    if (ref.size() == null || ref.sha256() == null) {
      throw new DaemonProtocolException(
          "resource ref must declare size and sha256 for the daemon wire: " + ref.uri());
    }
    if (bytes.length != ref.size()) {
      throw new DaemonProtocolException(
          "resource size mismatch for "
              + ref.uri()
              + ": declared="
              + ref.size()
              + " actual="
              + bytes.length);
    }
    if (!ref.sha256().equals(sha256Hex(bytes))) {
      throw new DaemonProtocolException("resource sha256 mismatch for " + ref.uri());
    }
    wireContent.put("type", "resource");
    wireContent.put("uri", ref.uri());
    wireContent.put("mediaType", ref.mediaType());
    putNullableText(wireContent, "name", ref.name());
    putNullableLong(wireContent, "size", ref.size());
    putNullableText(wireContent, "sha256", ref.sha256());
    wireContent.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
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

  private Long optionalLongOrNull(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(
          context + " '" + field + "' must be a long integer or null");
    }
    long result = value.longValue();
    if (result < 0) {
      throw new DaemonProtocolException(context + " '" + field + "' must not be negative");
    }
    return result;
  }

  private long requiredLong(ObjectNode obj, String field, String context) {
    Long value = optionalLongOrNull(obj, field, context);
    if (value == null) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    return value;
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

  private long canonicalBase64Length(long sizeBytes) {
    long groups = sizeBytes / 3;
    if (sizeBytes % 3 != 0) {
      groups++;
    }
    return groups > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : groups * 4;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static void putNullableLong(ObjectNode node, String field, Long value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.longValue());
    }
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
