package fun.fengwk.kkstudio.platform.daemon.tunnel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP token、origin-form 与多值头的共享校验。 */
final class TunnelHttp {

  private TunnelHttp() {}

  static String requireToken(String token, String name) {
    if (token == null || token.isEmpty()) {
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
    if (path == null || path.isEmpty()) {
      throw new IllegalArgumentException("path must be an origin-form starting with '/'");
    }
    URI uri;
    try {
      uri = new URI(path);
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("path must be a valid origin-form", error);
    }
    if (uri.getScheme() != null
        || uri.getRawAuthority() != null
        || uri.getFragment() != null
        || uri.getRawPath() == null
        || !uri.getRawPath().startsWith("/")
        || !originText(uri).equals(path)) {
      throw new IllegalArgumentException("path must be an origin-form without scheme or authority");
    }
    return path;
  }

  static Map<String, List<String>> freezeHeaders(Map<String, List<String>> headers) {
    if (headers == null) {
      throw new IllegalArgumentException("headers must not be null");
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

  private static String originText(URI uri) {
    String query = uri.getRawQuery();
    return query == null ? uri.getRawPath() : uri.getRawPath() + "?" + query;
  }

  private static List<String> freezeValues(String name, List<String> values) {
    if (values == null) {
      throw new IllegalArgumentException("headers[" + name + "] must not be null");
    }
    if (values.isEmpty()) {
      throw new IllegalArgumentException("header must have at least one value: " + name);
    }
    List<String> frozen = new ArrayList<>(values.size());
    for (String value : values) {
      if (value == null) {
        throw new IllegalArgumentException("headers[" + name + "] must not contain null");
      }
      if (containsInjection(value)) {
        throw new IllegalArgumentException("header value must not contain CR or LF: " + name);
      }
      frozen.add(value);
    }
    return List.copyOf(frozen);
  }

  private static String requireHeaderName(String name) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("header name must not be empty");
    }
    for (int index = 0; index < name.length(); index++) {
      if (!isTokenChar(name.charAt(index))) {
        throw new IllegalArgumentException("header name must be an HTTP token");
      }
    }
    return name.toLowerCase(Locale.ROOT);
  }

  private static boolean containsInjection(String value) {
    return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
  }

  private static boolean isTokenChar(char value) {
    return value > 0x20 && value < 0x7F && "\"(),/:;<=>?@[\\]{}".indexOf(value) < 0;
  }
}
