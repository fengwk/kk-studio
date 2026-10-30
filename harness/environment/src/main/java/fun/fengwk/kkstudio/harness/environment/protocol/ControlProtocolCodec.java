package fun.fengwk.kkstudio.harness.environment.protocol;

import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * v8 控制协议三个首批消息的 protobuf binary codec 与应用层校验。
 *
 * <p>protobuf 原生负责 wire 类型和字段号；这里补上 proto3 无法表达的契约：协议版本、消息类型与 payload
 * 一致性、scope、必填、枚举未指定值、安全整数、UUID/SHA/媒体类型、UTF-8 字节上限、既有 granular limits， 以及递归未知字段。嵌入 JSON
 * 字段只做严格文本校验，绝不重新序列化。
 *
 * <p>当前只覆盖 INVOKE、COMPLETED、EXECUTION_EXITED。SETTINGS 及其资源预算、heartbeat、cancel、
 * RECOVER_NOT_FOUND、426 升级拒绝等消息尚未进入本检查点。
 */
public final class ControlProtocolCodec {

  /** 控制协议版本；现有 v7 运行路径不受影响。 */
  public static final int PROTOCOL_VERSION = 8;

  /** JavaScript 可无损表示的最大整数。 */
  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  /** invocationId 的字符上限。 */
  public static final int MAX_INVOCATION_ID_CHARS = 256;

  /** capabilityVersion 的字符上限。 */
  public static final int MAX_CAPABILITY_VERSION_CHARS = 128;

  /** callId 的字符上限。 */
  public static final int MAX_CALL_ID_CHARS = 256;

  /** argumentsJson 的 UTF-8 上限；与单条帧上限一致。 */
  public static final int MAX_ARGUMENTS_JSON_UTF8_BYTES = ControlFraming.MAX_MESSAGE_BYTES;

  /** detailsJson 的 UTF-8 上限。 */
  public static final int MAX_DETAILS_JSON_UTF8_BYTES =
      EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES;

  /** JSON 内容文本的 UTF-8 上限。 */
  public static final int MAX_JSON_CONTENT_UTF8_BYTES = JsonResultContent.MAX_JSON_UTF8_BYTES;

  /** contents 数组元素上限。 */
  public static final int MAX_CONTENT_ITEMS = EnvironmentCapabilityResult.MAX_CONTENT_ITEMS;

  /** resource mediaType 的 ASCII 字节上限。 */
  public static final int MAX_MEDIA_TYPE_ASCII_BYTES = ResourceRef.MAX_MEDIA_TYPE_ASCII_BYTES;

  /** resource name 的 UTF-8 上限。 */
  public static final int MAX_NAME_UTF8_BYTES = ResourceRef.MAX_NAME_UTF8_BYTES;

  /** resource preview 的 UTF-8 上限。 */
  public static final int MAX_PREVIEW_UTF8_BYTES = ResourceRef.MAX_PREVIEW_UTF8_BYTES;

  private static final Pattern SHA256_LOWER_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern MEDIA_TYPE_PATTERN =
      Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");

  private ControlProtocolCodec() {}

  /** 解码一条裸 protobuf 消息并施加全部应用校验。 */
  public static Envelope decodeBinary(byte[] bytes) {
    requireMessageLength(bytes);
    try {
      return validate(Envelope.parseFrom(bytes));
    } catch (InvalidProtocolBufferException error) {
      throw new ControlProtocolException("control message is not valid protobuf", error);
    }
  }

  /** 校验后编码一条裸 protobuf 消息。 */
  public static byte[] encodeBinary(Envelope envelope) {
    validate(envelope);
    int size = envelope.getSerializedSize();
    if (size == 0 || size > ControlFraming.MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + ControlFraming.MAX_MESSAGE_BYTES + " bytes");
    }
    return envelope.toByteArray();
  }

  /** 解码一条带四字节长度前缀的帧。 */
  public static Envelope decodeFrame(byte[] frame) {
    return decodeBinary(ControlFraming.unframe(frame));
  }

  /** 校验后编码为一条带四字节长度前缀的帧。 */
  public static byte[] encodeFrame(Envelope envelope) {
    return ControlFraming.frame(encodeBinary(envelope));
  }

  /** 校验 envelope 的类型、scope、payload 一致性与全部业务必填/限额。 */
  public static Envelope validate(Envelope envelope) {
    if (envelope == null) {
      throw new ControlProtocolException("envelope must not be null");
    }
    requireNoUnknown(envelope, "envelope");
    if (!envelope.hasProtocolVersion() || envelope.getProtocolVersion() != PROTOCOL_VERSION) {
      throw new ControlProtocolException("protocolVersion must be " + PROTOCOL_VERSION);
    }
    MessageType messageType = envelope.getMessageType();
    if (messageType == MessageType.MESSAGE_TYPE_UNSPECIFIED
        || messageType == MessageType.UNRECOGNIZED) {
      throw new ControlProtocolException("messageType must be a known non-unspecified value");
    }
    validateScope(envelope);
    switch (messageType) {
      case INVOKE -> {
        requirePayload(envelope.getPayloadCase(), Envelope.PayloadCase.INVOKE, "INVOKE");
        validateInvoke(envelope.getInvoke());
      }
      case COMPLETED -> {
        requirePayload(envelope.getPayloadCase(), Envelope.PayloadCase.COMPLETED, "COMPLETED");
        validateCompleted(envelope.getCompleted());
      }
      case EXECUTION_EXITED -> requirePayload(
          envelope.getPayloadCase(), Envelope.PayloadCase.EXECUTION_EXITED, "EXECUTION_EXITED");
      default -> throw new ControlProtocolException("unsupported messageType: " + messageType);
    }
    return envelope;
  }

  private static void validateScope(Envelope envelope) {
    requireUuid(envelope.hasEnvironmentId(), envelope.getEnvironmentId(), "environmentId");
    requireText(
        envelope.hasInvocationId(),
        envelope.getInvocationId(),
        "invocationId",
        MAX_INVOCATION_ID_CHARS,
        true);
  }

  private static void validateInvoke(Invoke invoke) {
    String capabilityId =
        requireText(
            invoke.hasCapabilityId(),
            invoke.getCapabilityId(),
            "invoke.capabilityId",
            EnvironmentCapabilityId.MAX_LENGTH,
            true);
    requireCapabilityId(capabilityId);
    requireText(
        invoke.hasCapabilityVersion(),
        invoke.getCapabilityVersion(),
        "invoke.capabilityVersion",
        MAX_CAPABILITY_VERSION_CHARS,
        true);
    requireJsonObject(
        invoke.hasArgumentsJson(),
        invoke.getArgumentsJson(),
        "invoke.argumentsJson",
        MAX_ARGUMENTS_JSON_UTF8_BYTES);
    requireInt64(
        invoke.hasTimeoutMillis(),
        invoke.getTimeoutMillis(),
        "invoke.timeoutMillis",
        0,
        MAX_SAFE_INTEGER);
    if (invoke.hasExpectedFile()) {
      validateExpectedFile(capabilityId, invoke.getExpectedFile());
    }
  }

  private static void validateExpectedFile(String capabilityId, ExpectedFile expected) {
    if (!"fs.edit".equals(capabilityId)) {
      throw new ControlProtocolException("invoke.expectedFile is only valid for fs.edit");
    }
    requireText(
        expected.hasPath(),
        expected.getPath(),
        "invoke.expectedFile.path",
        Integer.MAX_VALUE,
        true);
    requirePattern(
        expected.hasSha256(),
        expected.getSha256(),
        "invoke.expectedFile.sha256",
        SHA256_LOWER_PATTERN);
  }

  private static void validateCompleted(Completed completed) {
    if (!completed.hasResult()) {
      throw new ControlProtocolException("completed.result is required");
    }
    CapabilityResult result = completed.getResult();
    requireText(
        result.hasCallId(), result.getCallId(), "completed.result.callId", MAX_CALL_ID_CHARS, true);
    if (!result.hasError()) {
      throw new ControlProtocolException("completed.result.error is required");
    }
    requireJsonObject(
        result.hasDetailsJson(),
        result.getDetailsJson(),
        "completed.result.detailsJson",
        MAX_DETAILS_JSON_UTF8_BYTES);
    if (result.getContentsCount() > MAX_CONTENT_ITEMS) {
      throw new ControlProtocolException(
          "completed.result.contents must not exceed " + MAX_CONTENT_ITEMS + " items");
    }
    for (int index = 0; index < result.getContentsCount(); index++) {
      validateContent(result.getContents(index), "completed.result.contents[" + index + "]");
    }
  }

  private static void validateContent(Content content, String path) {
    requireNoUnknown(content, path);
    switch (content.getBodyCase()) {
      case TEXT -> {
        TextContent text = content.getText();
        requireText(text.hasText(), text.getText(), path + ".text", Integer.MAX_VALUE, false);
      }
      case JSON -> {
        JsonContent json = content.getJson();
        requireJsonValue(
            json.hasJson(), json.getJson(), path + ".json", MAX_JSON_CONTENT_UTF8_BYTES);
      }
      case RESOURCE -> validateResource(content.getResource(), path);
      default -> throw new ControlProtocolException(
          path + " must declare exactly one content body");
    }
  }

  private static void validateResource(ResourceContent resource, String path) {
    requireUuid(resource.hasUploadId(), resource.getUploadId(), path + ".uploadId");
    String mediaType =
        requireText(
            resource.hasMediaType(),
            resource.getMediaType(),
            path + ".mediaType",
            MAX_MEDIA_TYPE_ASCII_BYTES,
            true);
    if (!MEDIA_TYPE_PATTERN.matcher(mediaType).matches()) {
      throw new ControlProtocolException(
          path + ".mediaType must be canonical lowercase type/subtype");
    }
    if (resource.hasName()) {
      String name =
          requireUtf8Text(true, resource.getName(), path + ".name", MAX_NAME_UTF8_BYTES, true);
      if (name.codePoints().anyMatch(Character::isISOControl)) {
        throw new ControlProtocolException(path + ".name must not contain control characters");
      }
    }
    requireInt64(resource.hasSize(), resource.getSize(), path + ".size", 0, MAX_SAFE_INTEGER);
    requirePattern(
        resource.hasSha256(), resource.getSha256(), path + ".sha256", SHA256_LOWER_PATTERN);
    if (resource.hasPreview()) {
      requireUtf8Text(
          true, resource.getPreview(), path + ".preview", MAX_PREVIEW_UTF8_BYTES, false);
    }
  }

  private static void requirePayload(
      Envelope.PayloadCase actual, Envelope.PayloadCase expected, String messageType) {
    if (actual != expected) {
      throw new ControlProtocolException(messageType + " envelope must carry the matching payload");
    }
  }

  private static void requireMessageLength(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > ControlFraming.MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + ControlFraming.MAX_MESSAGE_BYTES + " bytes");
    }
  }

  private static void requireCapabilityId(String capabilityId) {
    try {
      new EnvironmentCapabilityId(capabilityId);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
  }

  private static void requireJsonObject(
      boolean present, String value, String name, int maxUtf8Bytes) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    ProtocolJson.requireObject(value, name, maxUtf8Bytes);
  }

  private static void requireJsonValue(
      boolean present, String value, String name, int maxUtf8Bytes) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    ProtocolJson.requireValue(value, name, maxUtf8Bytes);
  }

  private static String requireText(
      boolean present, String value, String name, int maxChars, boolean nonBlank) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (nonBlank && value.isBlank()) {
      throw new ControlProtocolException(name + " must not be blank");
    }
    requireValidUnicode(value, name);
    if (value.length() > maxChars) {
      throw new ControlProtocolException(name + " exceeds " + maxChars + " characters");
    }
    return value;
  }

  private static String requireUtf8Text(
      boolean present, String value, String name, int maxUtf8Bytes, boolean nonBlank) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (nonBlank && value.isBlank()) {
      throw new ControlProtocolException(name + " must not be blank");
    }
    int length;
    try {
      length = ResourceRef.utf8LengthUpTo(value, name, maxUtf8Bytes);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
    if (length > maxUtf8Bytes) {
      throw new ControlProtocolException(name + " exceeds " + maxUtf8Bytes + " UTF-8 bytes");
    }
    return value;
  }

  private static void requireValidUnicode(String value, String name) {
    try {
      ResourceRef.utf8Length(value, name);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
  }

  private static String requirePattern(
      boolean present, String value, String name, Pattern pattern) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (!pattern.matcher(value).matches()) {
      throw new ControlProtocolException(name + " has invalid syntax");
    }
    return value;
  }

  private static void requireUuid(boolean present, String value, String name) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    try {
      if (!UUID.fromString(value).toString().equals(value)) {
        throw new ControlProtocolException(name + " must be a canonical lowercase UUID");
      }
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(name + " must be a canonical lowercase UUID", error);
    }
  }

  private static void requireInt64(
      boolean present, long value, String name, long minimum, long maximum) {
    if (!present || value < minimum || value > maximum) {
      throw new ControlProtocolException(
          name + " must be present and in " + minimum + ".." + maximum);
    }
  }

  private static void requireNoUnknown(Message message, String path) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new ControlProtocolException(path + " contains unknown protobuf fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
      if (entry.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
      Object value = entry.getValue();
      String childPath = path + "." + entry.getKey().getJsonName();
      if (value instanceof Message child) {
        requireNoUnknown(child, childPath);
      } else if (value instanceof List<?> children) {
        for (int index = 0; index < children.size(); index++) {
          if (children.get(index) instanceof Message child) {
            requireNoUnknown(child, childPath + "[" + index + "]");
          }
        }
      }
    }
  }
}
