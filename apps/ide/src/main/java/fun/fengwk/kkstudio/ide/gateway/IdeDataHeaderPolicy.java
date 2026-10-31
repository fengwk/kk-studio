package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IDE 数据面的 header 边界：只转发安全、有序的应用头，凭据、连接级头与代理链永远不跨越边界。
 *
 * <p>浏览器 cookie/authorization/origin 不进入 Daemon；code-server 的 {@code Set-Cookie} 也不回到浏览器。
 */
public final class IdeDataHeaderPolicy {

  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "proxy-connection",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade");

  private static final Set<String> DROPPED_REQUEST_HEADERS =
      Set.of("host", "content-length", "connection", "upgrade", "transfer-encoding", "expect");

  private static final Set<String> NEVER_FORWARDED_REQUEST_HEADERS =
      Set.of(
          "cookie",
          "origin",
          "referer",
          "authorization",
          "proxy-authorization",
          "forwarded",
          "sec-websocket-key",
          "sec-websocket-version",
          "sec-websocket-extensions");

  private static final List<String> NEVER_FORWARDED_REQUEST_HEADER_PREFIXES =
      List.of("x-kk-cluster-", "x-kk-origin-scheme", "x-forwarded-");

  private static final Set<String> NEVER_FORWARDED_RESPONSE_HEADERS =
      Set.of("content-length", "set-cookie", "connection", "upgrade");

  private IdeDataHeaderPolicy() {}

  /** 转发到 Daemon 的请求头。 */
  public static Map<String, List<String>> forwardRequestHeaders(
      HttpServletRequest request, boolean webSocket) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names != null) {
      Set<String> connectionTokens = connectionTokens(request);
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        if (!isValidHeaderName(name)) {
          continue;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (isNeverForwarded(lower, webSocket) || connectionTokens.contains(lower)) {
          continue;
        }
        Enumeration<String> values = request.getHeaders(name);
        if (values != null) {
          List<String> list = new ArrayList<>();
          while (values.hasMoreElements()) {
            String val = values.nextElement();
            if (isValidHeaderValue(val)) {
              list.add(val);
            }
          }
          if (!list.isEmpty()) {
            headers.put(name, list);
          }
        }
      }
    }
    if (webSocket) {
      addHeaderIfPresent(request, headers, "Sec-WebSocket-Key");
      addHeaderIfPresent(request, headers, "Sec-WebSocket-Version");
      addHeaderIfPresent(request, headers, "Sec-WebSocket-Protocol");
      addHeaderIfPresent(request, headers, "Sec-WebSocket-Extensions");
    }
    return headers;
  }

  private static void addHeaderIfPresent(
      HttpServletRequest request, Map<String, List<String>> headers, String name) {
    if (!isValidHeaderName(name)) {
      return;
    }
    Enumeration<String> values = request.getHeaders(name);
    if (values != null && values.hasMoreElements()) {
      List<String> list = new ArrayList<>();
      while (values.hasMoreElements()) {
        String val = values.nextElement();
        if (isValidHeaderValue(val)) {
          list.add(val);
        }
      }
      if (!list.isEmpty()) {
        headers.put(name, list);
      }
    }
  }

  public static boolean isHopByHop(String name) {
    return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
  }

  public static boolean isNeverForwarded(String lowerName, boolean webSocket) {
    if (HOP_BY_HOP.contains(lowerName) || DROPPED_REQUEST_HEADERS.contains(lowerName)) {
      return true;
    }
    if (NEVER_FORWARDED_REQUEST_HEADERS.contains(lowerName)) {
      return true;
    }
    if ("sec-websocket-protocol".equals(lowerName)) {
      return !webSocket;
    }
    for (String prefix : NEVER_FORWARDED_REQUEST_HEADER_PREFIXES) {
      if (lowerName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isForwardableResponseHeader(String lowerName, boolean webSocket) {
    return !NEVER_FORWARDED_RESPONSE_HEADERS.contains(lowerName)
        && !HOP_BY_HOP.contains(lowerName)
        && !("sec-websocket-protocol".equals(lowerName) && !webSocket);
  }

  public static Set<String> connectionTokens(HttpServletRequest request) {
    Set<String> tokens = new LinkedHashSet<>();
    Enumeration<String> values = request.getHeaders("Connection");
    if (values == null) {
      return tokens;
    }
    while (values.hasMoreElements()) {
      for (String token : values.nextElement().split(",")) {
        String trimmed = token.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) {
          tokens.add(trimmed);
        }
      }
    }
    return tokens;
  }

  /** 把上游响应头应用到浏览器响应；Location 与 Service-Worker-Allowed 保持在 view 作用域内。 */
  public static void applyResponseHeaders(
      int status,
      Map<String, List<String>> headers,
      HttpServletRequest request,
      HttpServletResponse response,
      EnvironmentId environmentId,
      UUID viewId,
      boolean webSocket) {
    response.setStatus(status);
    if (headers == null) {
      return;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (!isValidHeaderName(entry.getKey())) {
        continue;
      }
      String lower = entry.getKey().toLowerCase(Locale.ROOT);
      if (!isForwardableResponseHeader(lower, webSocket)) {
        continue;
      }
      for (String value : entry.getValue()) {
        if (!isValidHeaderValue(value)) {
          continue;
        }
        if ("location".equals(lower)) {
          String location =
              request instanceof IdeForwardedRequest
                  ? value
                  : IdeDataPaths.rebaseLocation(value, request, environmentId, viewId);
          response.addHeader(entry.getKey(), location);
        } else if ("service-worker-allowed".equals(lower)) {
          response.addHeader(
              entry.getKey(),
              request instanceof IdeForwardedRequest
                  ? value
                  : IdeDataPaths.rebaseWorkerScope(value, request, environmentId, viewId));
        } else {
          response.addHeader(entry.getKey(), value);
        }
      }
    }
  }

  public static boolean isValidHeaderName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      if (!isTokenCharacter(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static boolean isValidHeaderValue(String value) {
    if (value == null) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\r' || c == '\n' || c == '\0' || (c < 0x20 && c != '\t') || c == 0x7F) {
        return false;
      }
    }
    return true;
  }

  private static boolean isTokenCharacter(char c) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
      return true;
    }
    return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
  }

  public static void sendError(HttpServletResponse response, int status) throws IOException {
    if (!response.isCommitted()) {
      response.reset();
      response.sendError(status);
    }
  }
}
