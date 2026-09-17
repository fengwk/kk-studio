package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * invocation 作用域资源上传控制消息的严格 codec。
 *
 * <p>wire shape：
 *
 * <ul>
 *   <li>{@code RESOURCE_UPLOAD_REQUEST}（Daemon → 服务端）：{@code
 *       {"transferId":uuid,"mediaType":string,"name":string|null,"size":long,"sha256":string}}；
 *   <li>{@code RESOURCE_UPLOAD_COMMIT}（Daemon → 服务端）：{@code {"transferId":uuid,"uploadId":uuid}}；
 *   <li>{@code RESOURCE_UPLOAD_TICKET}（服务端 → Daemon）：{@code
 *       {"transferId":uuid,"state":"PENDING"|"READY"|"FAILED",...}}。{@code PENDING} 必须携带 {@code
 *       uploadId} 与 {@code presignedPut}；{@code READY} 必须携带 {@code uploadId}；{@code FAILED}
 *       必须只带一个有界 {@code message}。
 * </ul>
 *
 * <p>每一侧都拒绝未知字段、缺失字段、重复键、尾随内容以及超出上界的字符串，因此两个方向不会对同一状态产生不同解释。
 */
public final class DaemonResourceTransferCodec {

  /** 传输标识在 payload 中的字段名；同一调用内唯一。 */
  public static final String FIELD_TRANSFER_ID = "transferId";

  /** {@code FAILED} 状态下的有界失败说明长度上限。 */
  public static final int MAX_FAILURE_MESSAGE_CHARS = 512;

  /** 单条控制消息的字符上限：控制面只承载元数据，绝无二进制。 */
  public static final int MAX_PAYLOAD_CHARS = 16 * 1024;

  private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxStringLength(MAX_PAYLOAD_CHARS)
                          .maxNestingDepth(20)
                          .maxNumberLength(64)
                          .maxDocumentLength(MAX_PAYLOAD_CHARS)
                          .build())
                  .build())
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> REQUEST_FIELDS =
      Set.of(FIELD_TRANSFER_ID, "mediaType", "name", "size", "sha256");
  private static final Set<String> COMMIT_FIELDS = Set.of(FIELD_TRANSFER_ID, "uploadId");
  private static final Set<String> PRESIGNED_FIELDS =
      Set.of("method", "url", "headers", "expiresAt");

  /** Daemon → 服务端：申请一个 invocation 作用域的资源上传票据。 */
  public record UploadRequest(
      UUID transferId, String mediaType, String name, long size, String sha256) {

    public UploadRequest {
      Objects.requireNonNull(transferId, "transferId");
      if (mediaType == null || mediaType.isBlank()) {
        throw new IllegalArgumentException("mediaType must not be blank");
      }
      if (name != null && name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank when present");
      }
      if (size < 0) {
        throw new IllegalArgumentException("size must not be negative");
      }
      if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
        throw new IllegalArgumentException("sha256 must be 64 lowercase hex digits");
      }
    }
  }

  /** Daemon → 服务端：提交已直传完成的上传，等待 READY。 */
  public record UploadCommit(UUID transferId, UUID uploadId) {

    public UploadCommit {
      Objects.requireNonNull(transferId, "transferId");
      Objects.requireNonNull(uploadId, "uploadId");
    }
  }

  /** 服务端 → Daemon：票据状态。 */
  public enum TicketState {
    /** 对象尚未存在：Daemon 必须按 {@code presignedPut} 直传后再提交。 */
    PENDING,
    /** 内容已就绪（去重命中或提交完成）：可直接写入终态引用。 */
    READY,
    /** 本次票据动作失败：Daemon 可在 invocation deadline 内以同一 transfer 重试。 */
    FAILED
  }

  /** 服务端 → Daemon：票据事实；非空字段由 {@code state} 唯一决定。 */
  public record UploadTicket(
      UUID transferId,
      TicketState state,
      UUID uploadId,
      DaemonPresignedPut presignedPut,
      String message) {

    public UploadTicket {
      Objects.requireNonNull(transferId, "transferId");
      Objects.requireNonNull(state, "state");
      switch (state) {
        case PENDING -> {
          if (uploadId == null || presignedPut == null || message != null) {
            throw new IllegalArgumentException(
                "PENDING ticket requires uploadId and presignedPut only");
          }
        }
        case READY -> {
          if (uploadId == null || presignedPut != null || message != null) {
            throw new IllegalArgumentException("READY ticket requires uploadId only");
          }
        }
        case FAILED -> {
          if (message == null || message.isBlank() || uploadId != null || presignedPut != null) {
            throw new IllegalArgumentException("FAILED ticket requires a non-blank message only");
          }
          if (message.length() > MAX_FAILURE_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                "FAILED ticket message must not exceed "
                    + MAX_FAILURE_MESSAGE_CHARS
                    + " characters");
          }
        }
      }
    }

    public static UploadTicket pending(
        UUID transferId, UUID uploadId, DaemonPresignedPut presignedPut) {
      return new UploadTicket(transferId, TicketState.PENDING, uploadId, presignedPut, null);
    }

    public static UploadTicket ready(UUID transferId, UUID uploadId) {
      return new UploadTicket(transferId, TicketState.READY, uploadId, null, null);
    }

    public static UploadTicket failed(UUID transferId, String message) {
      String bounded = message == null || message.isBlank() ? "resource upload failed" : message;
      if (bounded.length() > MAX_FAILURE_MESSAGE_CHARS) {
        bounded = bounded.substring(0, MAX_FAILURE_MESSAGE_CHARS);
      }
      return new UploadTicket(transferId, TicketState.FAILED, null, null, bounded);
    }
  }

  public String encodeRequest(UploadRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode root = MAPPER.createObjectNode();
    root.put(FIELD_TRANSFER_ID, request.transferId().toString());
    root.put("mediaType", request.mediaType());
    putNullableText(root, "name", request.name());
    root.put("size", request.size());
    root.put("sha256", request.sha256());
    return write(root, "upload request");
  }

  public UploadRequest decodeRequest(String payloadJson) {
    ObjectNode root = readRoot(payloadJson, "upload request");
    rejectUnknownFields(root, REQUEST_FIELDS, "upload request");
    UUID transferId = requiredUuid(root, FIELD_TRANSFER_ID, "upload request");
    String mediaType = requiredText(root, "mediaType", "upload request");
    String name = requiredNullableText(root, "name", "upload request");
    long size = requiredNonNegativeLong(root, "size", "upload request");
    String sha256 = requiredText(root, "sha256", "upload request");
    try {
      return new UploadRequest(transferId, mediaType, name, size, sha256);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("upload request is invalid: " + error.getMessage());
    }
  }

  public String encodeCommit(UploadCommit commit) {
    Objects.requireNonNull(commit, "commit");
    ObjectNode root = MAPPER.createObjectNode();
    root.put(FIELD_TRANSFER_ID, commit.transferId().toString());
    root.put("uploadId", commit.uploadId().toString());
    return write(root, "upload commit");
  }

  public UploadCommit decodeCommit(String payloadJson) {
    ObjectNode root = readRoot(payloadJson, "upload commit");
    rejectUnknownFields(root, COMMIT_FIELDS, "upload commit");
    UUID transferId = requiredUuid(root, FIELD_TRANSFER_ID, "upload commit");
    UUID uploadId = requiredUuid(root, "uploadId", "upload commit");
    return new UploadCommit(transferId, uploadId);
  }

  public String encodeTicket(UploadTicket ticket) {
    Objects.requireNonNull(ticket, "ticket");
    ObjectNode root = MAPPER.createObjectNode();
    root.put(FIELD_TRANSFER_ID, ticket.transferId().toString());
    root.put("state", ticket.state().name());
    switch (ticket.state()) {
      case PENDING -> {
        root.put("uploadId", ticket.uploadId().toString());
        ObjectNode presigned = root.putObject("presignedPut");
        presigned.put("method", ticket.presignedPut().method());
        presigned.put("url", ticket.presignedPut().url());
        ObjectNode headers = presigned.putObject("headers");
        ticket.presignedPut().headers().forEach(headers::put);
        // 签名过期时刻是 PENDING 票据的必要事实，以规范 ISO-8601 Instant 跨端保留。
        presigned.put("expiresAt", ticket.presignedPut().expiresAt().toString());
      }
      case READY -> root.put("uploadId", ticket.uploadId().toString());
      case FAILED -> root.put("message", ticket.message());
    }
    return write(root, "upload ticket");
  }

  public UploadTicket decodeTicket(String payloadJson) {
    ObjectNode root = readRoot(payloadJson, "upload ticket");
    UUID transferId = requiredUuid(root, FIELD_TRANSFER_ID, "upload ticket");
    String stateText = requiredText(root, "state", "upload ticket");
    TicketState state;
    try {
      state = TicketState.valueOf(stateText);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("upload ticket state is unknown: " + stateText);
    }
    try {
      return switch (state) {
        case PENDING -> {
          rejectUnknownFields(
              root,
              Set.of(FIELD_TRANSFER_ID, "state", "uploadId", "presignedPut"),
              "upload ticket");
          yield UploadTicket.pending(
              transferId,
              requiredUuid(root, "uploadId", "upload ticket"),
              decodePresignedPut(root.get("presignedPut")));
        }
        case READY -> {
          rejectUnknownFields(
              root, Set.of(FIELD_TRANSFER_ID, "state", "uploadId"), "upload ticket");
          yield UploadTicket.ready(transferId, requiredUuid(root, "uploadId", "upload ticket"));
        }
        case FAILED -> {
          rejectUnknownFields(root, Set.of(FIELD_TRANSFER_ID, "state", "message"), "upload ticket");
          yield UploadTicket.failed(transferId, requiredText(root, "message", "upload ticket"));
        }
      };
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("upload ticket is invalid: " + error.getMessage());
    }
  }

  /** 解析本次传输的 transferId：服务端用它把响应帧关联回正确请求。 */
  public UUID transferIdOf(String payloadJson) {
    ObjectNode root = readRoot(payloadJson, "upload control");
    return requiredUuid(root, FIELD_TRANSFER_ID, "upload control");
  }

  private DaemonPresignedPut decodePresignedPut(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new DaemonProtocolException("upload ticket presignedPut must be an object");
    }
    ObjectNode presigned = (ObjectNode) node;
    rejectUnknownFields(presigned, PRESIGNED_FIELDS, "upload ticket presignedPut");
    String method = requiredText(presigned, "method", "upload ticket presignedPut");
    String url = requiredText(presigned, "url", "upload ticket presignedPut");
    JsonNode headersNode = presigned.get("headers");
    if (headersNode == null || !headersNode.isObject()) {
      throw new DaemonProtocolException("upload ticket presignedPut.headers must be an object");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headersNode
        .fields()
        .forEachRemaining(
            entry -> {
              if (!entry.getValue().isTextual()) {
                throw new DaemonProtocolException(
                    "upload ticket presignedPut header values must be strings");
              }
              headers.put(entry.getKey(), entry.getValue().textValue());
            });
    Instant expiresAt = requiredInstant(presigned, "expiresAt", "upload ticket presignedPut");
    try {
      return new DaemonPresignedPut(method, url, headers, expiresAt);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("upload ticket presignedPut is invalid");
    }
  }

  private ObjectNode readRoot(String payloadJson, String context) {
    if (payloadJson == null || payloadJson.isBlank()) {
      throw new DaemonProtocolException(context + " payload must not be blank");
    }
    if (payloadJson.length() > MAX_PAYLOAD_CHARS) {
      throw new DaemonProtocolException(
          context + " payload must not exceed " + MAX_PAYLOAD_CHARS + " characters");
    }
    try {
      JsonNode value = MAPPER.readTree(payloadJson);
      if (!(value instanceof ObjectNode object)) {
        throw new DaemonProtocolException(context + " payload must be a JSON object");
      }
      return object;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException(context + " payload must be valid JSON", error);
    }
  }

  private String write(ObjectNode root, String context) {
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode " + context + " payload", error);
    }
  }

  private static void rejectUnknownFields(ObjectNode node, Set<String> allowed, String context) {
    List<String> unknown = new ArrayList<>();
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field)) {
                unknown.add(field);
              }
            });
    if (!unknown.isEmpty() || node.size() != allowed.size()) {
      throw new DaemonProtocolException(context + " payload has unexpected or missing fields");
    }
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String requiredNullableText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text or null");
    }
    return value.textValue();
  }

  private static long requiredNonNegativeLong(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(context + "." + field + " must be a long integer");
    }
    if (value.longValue() < 0) {
      throw new DaemonProtocolException(context + "." + field + " must not be negative");
    }
    return value.longValue();
  }

  private static UUID requiredUuid(ObjectNode node, String field, String context) {
    String text = requiredText(node, field, context);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw new DaemonProtocolException(
            context + "." + field + " must be a canonical UUID string");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(context + "." + field + " must be a canonical UUID string");
    }
  }

  /** 严格读取一个规范 ISO-8601 Instant 字段：必须可被 {@link Instant#parse} 接受且为规范形式。 */
  private static Instant requiredInstant(ObjectNode node, String field, String context) {
    String text = requiredText(node, field, context);
    try {
      Instant parsed = Instant.parse(text);
      if (!parsed.toString().equals(text)) {
        throw new DaemonProtocolException(
            context + "." + field + " must be a canonical ISO-8601 instant");
      }
      return parsed;
    } catch (DateTimeParseException error) {
      throw new DaemonProtocolException(
          context + "." + field + " must be a canonical ISO-8601 instant");
    }
  }

  private static void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
