package fun.fengwk.kkstudio.plugin.opencli;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubException.Kind;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * OpenCLI Hub 响应信封的字段读取、虚拟资源路径规范与固定去敏协议错误。
 *
 * <p>所有字段读取都拒绝缺失、null 与类型不符；错误消息只描述协议事实，绝不回显远端原值。
 */
@Slf4j
final class OpenCliHubProtocol {

  private static final Pattern VIRTUAL_RESOURCE_PATH =
      Pattern.compile("/resources/[A-Za-z0-9._~!$&'()+,;=:@%/-]+");

  private OpenCliHubProtocol() {}

  static OpenCliHubException malformed(String message) {
    return new OpenCliHubException(Kind.PROTOCOL, "Malformed OpenCLI Hub response: " + message);
  }

  static OpenCliHubException malformed(String message, Throwable cause) {
    log.warn(
        "Malformed OpenCLI Hub response: {} type={}", message, cause.getClass().getSimpleName());
    return new OpenCliHubException(Kind.PROTOCOL, "Malformed OpenCLI Hub response: " + message);
  }

  static JsonNode required(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw malformed("expected JSON object while reading " + field);
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw malformed(field + " is required");
    }
    return value;
  }

  static String requiredText(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw malformed(field + " must be non-blank text");
    }
    return value.textValue();
  }

  static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isBoolean()) {
      throw malformed(field + " must be boolean");
    }
    return value.booleanValue();
  }

  static long requiredLong(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw malformed(field + " must be an integer");
    }
    return value.longValue();
  }

  static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw malformed(field + " must be text or null");
    }
    return value.textValue();
  }

  static String optionalNullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw malformed(field + " must be non-blank text or null");
    }
    return value.textValue();
  }

  static boolean isCanonicalResourcePath(String resourcePath) {
    if (resourcePath == null || !VIRTUAL_RESOURCE_PATH.matcher(resourcePath).matches()) {
      return false;
    }
    URI parsed;
    try {
      parsed = URI.create(resourcePath);
    } catch (IllegalArgumentException exception) {
      return false;
    }
    return parsed.getQuery() == null
        && parsed.getFragment() == null
        && !hasDotSegment(parsed.getPath())
        && parsed.normalize().getPath() != null
        && parsed.normalize().getPath().startsWith("/resources/");
  }

  static void requireCanonicalResourcePath(String resourcePath) {
    try {
      URI.create(resourcePath);
    } catch (IllegalArgumentException exception) {
      throw malformed("upload resourcePath must be a canonical /resources/ path", exception);
    }
    if (!isCanonicalResourcePath(resourcePath)) {
      throw malformed("upload resourcePath must be a canonical /resources/ path");
    }
  }

  static boolean hasDotSegment(String path) {
    if (path == null) {
      return true;
    }
    for (String segment : path.split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        return true;
      }
    }
    return false;
  }
}
