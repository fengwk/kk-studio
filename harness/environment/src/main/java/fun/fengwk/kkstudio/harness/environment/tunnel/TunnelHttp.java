package fun.fengwk.kkstudio.harness.environment.tunnel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** HTTP token、origin-form 与多值头的共享校验。 */
final class TunnelHttp {

  private TunnelHttp() {}

  static String requireToken(String token, String name) {
    Objects.requireNonNull(token, name);
    if (token.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    for (int index = 0; index < token.length(); index++) {
      if (!isTokenChar(token.charAt(index))) {
        throw new IllegalArgumentException(name + " must be an HTTP token");
      }
    }
    return token;
  }

  static String requireOriginPath(String path) {
    Objects.requireNonNull(path, "path");
    if (path.isEmpty() || path.charAt(0) != '/') {
      throw new IllegalArgumentException("path must be an origin-form starting with '/'");
    }
    if (containsInjection(path) || hasSchemeOrAuthority(path) || path.indexOf('#') >= 0) {
      throw new IllegalArgumentException("path must not contain a scheme, authority, or fragment");
    }
    return path;
  }

  static Map<String, List<String>> freezeHeaders(Map<String, List<String>> headers) {
    if (headers == null) {
      return Map.of();
    }
    Map<String, List<String>> frozen = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String name = requireHeaderName(entry.getKey());
      if (frozen.containsKey(name)) {
        throw new IllegalArgumentException("duplicate header name: " + name);
      }
      frozen.put(name, freezeValues(name, entry.getValue()));
    }
    return Map.copyOf(frozen);
  }

  private static List<String> freezeValues(String name, List<String> values) {
    Objects.requireNonNull(values, "headers[" + name + "]");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("header must have at least one value: " + name);
    }
    List<String> frozen = new ArrayList<>(values.size());
    for (String value : values) {
      Objects.requireNonNull(value, "headers[" + name + "]");
      if (containsInjection(value)) {
        throw new IllegalArgumentException("header value must not contain CR or LF: " + name);
      }
      frozen.add(value);
    }
    return List.copyOf(frozen);
  }

  private static String requireHeaderName(String name) {
    Objects.requireNonNull(name, "header name");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("header name must not be empty");
    }
    for (int index = 0; index < name.length(); index++) {
      if (!isTokenChar(name.charAt(index))) {
        throw new IllegalArgumentException("header name must be an HTTP token");
      }
    }
    return name.toLowerCase(Locale.ROOT);
  }

  private static boolean hasSchemeOrAuthority(String path) {
    return path.startsWith("//") || path.indexOf("://") >= 0;
  }

  private static boolean containsInjection(String value) {
    return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
  }

  private static boolean isTokenChar(char value) {
    return value > 0x20 && value < 0x7F && "\"(),/:;<=>?@[\\]{}".indexOf(value) < 0;
  }
}
