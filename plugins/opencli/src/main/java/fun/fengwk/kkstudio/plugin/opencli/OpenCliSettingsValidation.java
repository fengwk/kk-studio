package fun.fengwk.kkstudio.plugin.opencli;

import java.net.URI;

/**
 * {@link OpenCliSettings} 及其嵌套 record 共享的无状态字段级校验工具。
 *
 * <p>本类不持有任何 static 常量或状态，只提供纯函数校验，供顶层 record 与嵌套 record 复用。
 */
final class OpenCliSettingsValidation {

  private OpenCliSettingsValidation() {}

  static void requireBounded(long value, long minimum, long maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
  }

  static void requireBounded(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
  }

  static String requireHttpOrigin(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    String trimmed = value.strip();
    URI uri;
    try {
      uri = URI.create(trimmed);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be an HTTP(S) origin");
    }
    int port = uri.getPort();
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (port != -1 && (port <= 0 || port > 65535))) {
      throw new IllegalArgumentException(
          field + " must be an HTTP(S) origin without path, userinfo, query or fragment");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !"/".equals(path)) {
      throw new IllegalArgumentException(field + " must be an HTTP(S) origin without path");
    }
    return trimmed;
  }

  static String requireOptionalBoundedText(String value, int maxLength, String field) {
    if (value == null) {
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    String stripped = value.strip();
    if (stripped.isEmpty() || stripped.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " must be absent or 1.." + maxLength + " characters");
    }
    return stripped;
  }
}
