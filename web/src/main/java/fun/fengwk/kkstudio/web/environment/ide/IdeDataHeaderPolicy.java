package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IDE 数据面的 header 边界：只转发安全、有序的应用头，凭据、连接级头与代理链永远不跨越边界。
 *
 * <p>浏览器 cookie/authorization/origin 不进入 Daemon；code-server 的 {@code Set-Cookie} 也不回到浏览器。WS 逻辑流额外保留
 * {@code Sec-WebSocket-Protocol}，由 Daemon 作为上游子协议候选使用。
 */
final class IdeDataHeaderPolicy {

  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade");

  private static final Set<String> DROPPED_REQUEST_HEADERS =
      Set.of("host", "content-length", "connection", "upgrade", "transfer-encoding");

  /** 绝不进入 Daemon 的请求头：浏览器凭据、内部认证、代理链与 WS 握手材料。 */
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

  /** 前缀级屏蔽：内部路由头与 {@code X-Forwarded-*} 代理链绝不透传。 */
  private static final List<String> NEVER_FORWARDED_REQUEST_HEADER_PREFIXES =
      List.of("x-kk-cluster-", "x-kk-origin-scheme", "x-forwarded-");

  /** 上游绝不能写给浏览器的响应头。 */
  private static final Set<String> NEVER_FORWARDED_RESPONSE_HEADERS =
      Set.of("content-length", "set-cookie", "connection", "upgrade");

  private IdeDataHeaderPolicy() {}

  /** 转发到 Daemon 的浏览器请求头；超过 OPEN 上限时按有序前缀截断。 */
  static List<MuxHeader> forwardRequestHeaders(HttpServletRequest request, boolean webSocket) {
    List<MuxHeader> headers = new ArrayList<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names == null) {
      return headers;
    }
    Set<String> connectionTokens = connectionTokens(request);
    while (names.hasMoreElements() && headers.size() < MuxLimits.MAX_OPEN_HEADERS) {
      String name = names.nextElement();
      String lower = name.toLowerCase(Locale.ROOT);
      if (isNeverForwarded(lower, webSocket) || connectionTokens.contains(lower)) {
        continue;
      }
      Enumeration<String> values = request.getHeaders(name);
      while (values.hasMoreElements() && headers.size() < MuxLimits.MAX_OPEN_HEADERS) {
        try {
          headers.add(new MuxHeader(name, values.nextElement()));
        } catch (IllegalArgumentException ignored) {
          // 非法 header 名/值不进入 wire；丢弃而不是让整个请求失败。
        }
      }
    }
    return headers;
  }

  /** 内部 WS 握手已把浏览器安全头带到 {@link HttpHeaders}；只保留与直连路径相同的可转发集合。 */
  static List<MuxHeader> forwardHandshakeHeaders(HttpHeaders headers, boolean webSocket) {
    List<MuxHeader> forwarded = new ArrayList<>();
    Set<String> connectionTokens = new LinkedHashSet<>();
    for (String token : headers.getOrDefault("Connection", List.of())) {
      for (String part : token.split(",")) {
        String trimmed = part.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) {
          connectionTokens.add(trimmed);
        }
      }
    }
    for (Map.Entry<String, List<String>> entry : headers.headerSet()) {
      String lower = entry.getKey().toLowerCase(Locale.ROOT);
      if (isNeverForwarded(lower, webSocket) || connectionTokens.contains(lower)) {
        continue;
      }
      for (String value : entry.getValue()) {
        if (forwarded.size() >= MuxLimits.MAX_OPEN_HEADERS) {
          return forwarded;
        }
        try {
          forwarded.add(new MuxHeader(entry.getKey(), value));
        } catch (IllegalArgumentException ignored) {
          // 非法 header 不进入 wire。
        }
      }
    }
    return forwarded;
  }

  static boolean isHopByHop(String name) {
    return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
  }

  /** 该请求头名是否永不转发给 Daemon。 */
  static boolean isNeverForwarded(String lowerName, boolean webSocket) {
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

  /**
   * 该响应头名是否可以返回浏览器。
   *
   * @param lowerName 小写 header 名
   * @param webSocket 是否 WS 逻辑流
   */
  static boolean isForwardableResponseHeader(String lowerName, boolean webSocket) {
    return !NEVER_FORWARDED_RESPONSE_HEADERS.contains(lowerName)
        && !HOP_BY_HOP.contains(lowerName)
        && !("sec-websocket-protocol".equals(lowerName) && !webSocket);
  }

  /** {@code Connection} 头列出的连接级头名（逗号分隔）。 */
  static Set<String> connectionTokens(HttpServletRequest request) {
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

  /** 把 HEADERS 应用到浏览器响应；Location 保持在 view 作用域内。 */
  static void applyResponseHeaders(
      MuxHeaders headers,
      HttpServletRequest request,
      HttpServletResponse response,
      EnvironmentId environmentId,
      UUID viewId) {
    response.setStatus(headers.status());
    for (MuxHeader header : headers.headers()) {
      String lower = header.name().toLowerCase(Locale.ROOT);
      if (!isForwardableResponseHeader(lower, false)) {
        continue;
      }
      if ("location".equals(lower)) {
        // 跨节点内部请求没有浏览器 view 上下文；由非 owner 的转发层统一重定位。
        String location =
            request instanceof IdeForwardedRequest
                ? header.value()
                : IdeGatewayDataPlane.rebaseLocation(
                    header.value(), request, environmentId, viewId);
        response.addHeader(header.name(), location);
      } else if ("service-worker-allowed".equals(lower)) {
        response.addHeader(
            header.name(),
            request instanceof IdeForwardedRequest
                ? header.value()
                : IdeGatewayDataPlane.rebaseWorkerScope(
                    header.value(), request, environmentId, viewId));
      } else {
        response.addHeader(header.name(), header.value());
      }
    }
  }

  static void sendError(HttpServletResponse response, int status) throws IOException {
    if (!response.isCommitted()) {
      response.reset();
      response.sendError(status);
    }
  }
}
