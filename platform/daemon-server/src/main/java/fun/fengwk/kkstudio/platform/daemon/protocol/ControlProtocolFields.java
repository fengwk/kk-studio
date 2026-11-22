package fun.fengwk.kkstudio.platform.daemon.protocol;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 控制协议字段级校验原语。
 *
 * <p>这里集中 proto3 无法表达的单个字段契约：必填、非空白、Unicode 与 UTF-8
 * 字节上限、字符上限、UUID/SHA/媒体类型/版本/时区形状、整型范围、单行文本与递归未知字段。失败一律抛 {@link
 * ControlProtocolException}，文案由字段名加固定英文后缀构成，供协议两端共享。
 *
 * <p>消息级语义（类型与 payload 一致性、字段组合、scope）留在 {@link ControlProtocolValidator} 及其资源/IDE 校验器。
 */
final class ControlProtocolFields {

  /** callId 的字符上限。 */
  static final int MAX_CALL_ID_CHARS = 256;

  static final int MAX_FAILURE_MESSAGE_CHARS = 512;
  static final int MAX_ERROR_MESSAGE_CHARS = 4096;
  static final int MAX_IDE_PATH_CHARS = 4096;
  static final Pattern SHA256_LOWER_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
  static final Pattern MEDIA_TYPE_PATTERN =
      Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");

  static void requireHttpOrigin(String value, String name, boolean proxy) {
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException error) {
      throw new ControlProtocolException(name + " must be an HTTP origin", error);
    }
    String scheme = uri.getScheme();
    boolean schemeAllowed =
        scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"));
    if (!schemeAllowed
        || (proxy && !scheme.equalsIgnoreCase("http"))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || (uri.getRawPath() != null
            && !uri.getRawPath().isEmpty()
            && !uri.getRawPath().equals("/"))) {
      throw new ControlProtocolException(
          name
              + (proxy
                  ? " must be an http://host:port proxy origin without userinfo, path, query or"
                      + " fragment"
                  : " must be an HTTP(S) origin without userinfo, path, query or fragment"));
    }
    if (proxy && uri.getPort() < 1) {
      throw new ControlProtocolException(name + " must declare an explicit port");
    }
    if (uri.getPort() > 65535) {
      throw new ControlProtocolException(name + " port is invalid");
    }
  }

  static void requirePayload(Envelope envelope, Envelope.PayloadCase expected, String messageType) {
    if (envelope.getPayloadCase() != expected) {
      throw new ControlProtocolException(messageType + " envelope must carry the matching payload");
    }
  }

  static void requireCapabilityId(String capabilityId) {
    try {
      new EnvironmentCapabilityId(capabilityId);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
  }

  static void requireJsonObject(boolean present, String value, String name, int maxUtf8Bytes) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    ProtocolJson.requireObject(value, name, maxUtf8Bytes);
  }

  static void requireJsonValue(boolean present, String value, String name, int maxUtf8Bytes) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    ProtocolJson.requireValue(value, name, maxUtf8Bytes);
  }

  static String requireText(
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

  static String requireUtf8Text(
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

  static void requireValidUnicode(String value, String name) {
    try {
      ResourceRef.utf8Length(value, name);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
  }

  static String requirePattern(boolean present, String value, String name, Pattern pattern) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (!pattern.matcher(value).matches()) {
      throw new ControlProtocolException(name + " has invalid syntax");
    }
    return value;
  }

  static void requireUuid(boolean present, String value, String name) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    try {
      Uuids.parseCanonical(value, name);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(name + " must be a canonical lowercase UUID", error);
    }
  }

  static void requireInt64(boolean present, long value, String name, long minimum, long maximum) {
    if (!present || value < minimum || value > maximum) {
      throw new ControlProtocolException(
          name + " must be present and in " + minimum + ".." + maximum);
    }
  }

  static void requirePositive(boolean present, long value, String name) {
    requireInt64(present, value, name, 1, Long.MAX_VALUE);
  }

  static String requireDaemonVersion(boolean present, String value, String name) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    try {
      return DaemonReadyMetadata.validateDaemonVersion(value);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(name + " is invalid: " + error.getMessage(), error);
    }
  }

  static void requireZone(boolean present, String value, String name) {
    String zone = requireText(present, value, name, 128, true);
    try {
      ZoneId.of(zone);
    } catch (RuntimeException error) {
      throw new ControlProtocolException(name + " must be a valid ZoneId", error);
    }
  }

  static void requireSingleLine(boolean present, String value, String name, int maxChars) {
    String text = requireText(present, value, name, maxChars, true);
    if (!text.equals(text.strip())) {
      throw new ControlProtocolException(name + " must not have surrounding whitespace");
    }
    if (text.codePoints().anyMatch(Character::isISOControl)) {
      throw new ControlProtocolException(name + " must not contain control characters");
    }
  }

  static void requireMediaType(boolean present, String value, String name) {
    String mediaType =
        requireText(present, value, name, ResourceRef.MAX_MEDIA_TYPE_ASCII_BYTES, true);
    if (!MEDIA_TYPE_PATTERN.matcher(mediaType).matches()) {
      throw new ControlProtocolException(name + " must be canonical lowercase type/subtype");
    }
  }

  static void requireBoundedMessage(boolean present, String value, String name) {
    requireText(present, value, name, MAX_ERROR_MESSAGE_CHARS, true);
  }

  static String requirePresent(boolean present, String value, String name) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    return value;
  }

  /**
   * IDE 路径：非空白、合法 Unicode、不超过 {@link #MAX_IDE_PATH_CHARS} 个码点且不含任何 Unicode 控制字符。
   *
   * <p>与 Daemon(Go) 的 {@code unicode.IsControl} 和 {@code RuneCountInString} 同口径，避免 Studio 放行而
   * Daemon 拒绝。
   */
  static void requireIdePath(String value, String name) {
    if (value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (value.isBlank()) {
      throw new ControlProtocolException(name + " must not be blank");
    }
    requireValidUnicode(value, name);
    if (value.codePointCount(0, value.length()) > MAX_IDE_PATH_CHARS) {
      throw new ControlProtocolException(name + " exceeds " + MAX_IDE_PATH_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new ControlProtocolException(name + " must not contain control characters");
    }
  }

  static void requireNoUnknown(Message message, String path) {
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
      } else if (value instanceof Map<?, ?> map) {
        for (Object child : map.values()) {
          if (child instanceof Message nested) {
            requireNoUnknown(nested, childPath);
          }
        }
      }
    }
  }
}
