package fun.fengwk.kkstudio.harness.provider.transport;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** HTTP 连接握手成功（2xx 且 text/event-stream）后的响应元数据。 */
public record HttpOpenMetadata(int statusCode, Map<String, List<String>> headers) {

  private static final Set<String> SENSITIVE_HEADERS =
      Set.of(
          "authorization",
          "proxy-authorization",
          "cookie",
          "set-cookie",
          "x-api-key",
          "api-key",
          "token",
          "x-auth-token");

  public HttpOpenMetadata {
    headers = sanitizeHeaders(headers);
  }

  static Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> clean = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach(
        (name, values) -> {
          if (name != null && !SENSITIVE_HEADERS.contains(name.toLowerCase())) {
            clean.put(name, values == null ? List.of() : List.copyOf(values));
          }
        });
    return Collections.unmodifiableMap(clean);
  }
}
