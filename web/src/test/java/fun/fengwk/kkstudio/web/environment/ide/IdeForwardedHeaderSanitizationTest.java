package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.protocol.IdeHeader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 两条转发出边界的 header 净化契约：浏览器 → Daemon（数据面）与 非 owner → owner（内部单跳）。
 *
 * <p>凭据（Studio 域 cookie、{@code Authorization}/{@code Proxy-Authorization}）、代理链、内部路由头与 WS
 * 握手材料在任何一条边界上都必须消失； 内部单跳只允许带一个按 cookie 名精确匹配的 IDE 会话 cookie。多值 header 必须逐值按序保留，不能被折叠。
 */
class IdeForwardedHeaderSanitizationTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final String IDE_COOKIE = "kk_ide_session";

  /** 浏览器 → Daemon：绝不让浏览器凭据、代理链、内部路由头或 WS 握手材料流入 code-server。 */
  @Test
  void daemonFacingHeadersDropCredentialsProxyChainAndHandshakeMaterial() {
    List<IdeHeader> headers = IdeGatewayDataPlane.forwardRequestHeaders(credentialedRequest());

    for (String forbidden :
        List.of(
            "cookie",
            "authorization",
            "proxy-authorization",
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-proto",
            "x-kk-cluster-target",
            "x-kk-cluster-signature",
            "origin",
            "referer",
            "sec-websocket-key",
            "sec-websocket-version",
            "connection",
            "transfer-encoding",
            "x-hop-token")) {
      assertFalse(has(headers, forbidden), forbidden + " must never reach the daemon");
    }
    assertTrue(has(headers, "content-type"), "ordinary request headers must survive");
  }

  /** 多值 header 逐值按序保留：不折叠成逗号拼接的单值，也不丢后续值。 */
  @Test
  void multiValueHeadersKeepEveryValueInOrder() {
    List<IdeHeader> headers = IdeGatewayDataPlane.forwardRequestHeaders(credentialedRequest());

    assertEquals(List.of("text/plain", "application/json"), values(headers, "accept"));
    assertEquals(List.of("one", "two"), values(headers, "x-multi"));
  }

  /** 非 owner → owner 内部单跳：Studio 域 cookie 与 Authorization 绝不跨节点，只带一个精确匹配的 IDE 会话 cookie。 */
  @Test
  void internalOwnerHeadersCarryOnlyTheIdeSessionCookie() {
    IdeGatewayProperties properties = properties();
    List<IdeHeader> headers =
        IdeRemoteDataPlaneForwarder.forwardedBrowserHeaders(credentialedRequest(), properties);

    assertEquals(
        List.of(IDE_COOKIE + "=ide-secret"),
        values(headers, "cookie"),
        "internal hop must carry exactly the IDE session cookie, never the Studio domain cookie");
    for (String forbidden :
        List.of(
            "authorization",
            "proxy-authorization",
            "forwarded",
            "x-forwarded-for",
            "x-kk-cluster-target",
            "origin",
            "referer",
            "sec-websocket-key",
            "connection",
            "x-hop-token")) {
      assertFalse(has(headers, forbidden), forbidden + " must never cross the node boundary");
    }
    assertEquals(List.of("text/plain", "application/json"), values(headers, "accept"));
    assertEquals(List.of("one", "two"), values(headers, "x-multi"));
  }

  /** 没有 IDE 会话 cookie 时，内部单跳不得凭空补 cookie；Studio 域 cookie 仍被剥离。 */
  @Test
  void internalOwnerHeadersWithoutIdeCookieCarryNoCookieAtAll() {
    MockHttpServletRequest request = credentialedRequest();
    request.setCookies(new Cookie("kk_studio_session", "studio-secret"));

    List<IdeHeader> headers =
        IdeRemoteDataPlaneForwarder.forwardedBrowserHeaders(request, properties());

    assertFalse(has(headers, "cookie"), "Studio-only cookies must not be forwarded");
  }

  /** 带 Studio 域 cookie 与浏览器凭据的真实形态请求。 */
  private static MockHttpServletRequest credentialedRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspace/x.txt");
    request.setServerName(ENV + ".ide.test");
    request.addHeader("Host", ENV + ".ide.test");
    request.addHeader("Cookie", "kk_studio_session=studio-secret; " + IDE_COOKIE + "=ide-secret");
    request.setCookies(
        new Cookie("kk_studio_session", "studio-secret"), new Cookie(IDE_COOKIE, "ide-secret"));
    request.addHeader("Authorization", "Bearer studio-token");
    request.addHeader("Proxy-Authorization", "Basic cHJveHk=");
    request.addHeader("Forwarded", "for=10.0.0.1");
    request.addHeader("X-Forwarded-For", "10.0.0.1");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-KK-Cluster-Target", "22222222-2222-2222-2222-222222222222");
    request.addHeader("X-KK-Cluster-Signature", "forged");
    request.addHeader("Origin", "http://" + ENV + ".ide.test");
    request.addHeader("Referer", "http://" + ENV + ".ide.test/");
    request.addHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
    request.addHeader("Sec-WebSocket-Version", "13");
    request.addHeader("Connection", "keep-alive, x-hop-token");
    request.addHeader("x-hop-token", "1");
    request.addHeader("Transfer-Encoding", "chunked");
    request.addHeader("Content-Type", "text/plain");
    request.addHeader("Accept", "text/plain");
    request.addHeader("Accept", "application/json");
    request.addHeader("X-Multi", "one");
    request.addHeader("X-Multi", "two");
    return request;
  }

  private static IdeGatewayProperties properties() {
    IdeGatewayProperties properties = new IdeGatewayProperties();
    properties.setSessionCookieName(IDE_COOKIE);
    return properties;
  }

  private static boolean has(List<IdeHeader> headers, String lowerName) {
    return !values(headers, lowerName).isEmpty();
  }

  private static List<String> values(List<IdeHeader> headers, String lowerName) {
    List<String> values = new ArrayList<>();
    for (IdeHeader header : headers) {
      if (header.name().toLowerCase(Locale.ROOT).equals(lowerName)) {
        values.add(header.value());
      }
    }
    return values;
  }
}
