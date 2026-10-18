package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * 浏览器 WS 握手的同源准入：必须精确匹配 Studio Origin，堵住同站其它子域/跨站脚本对 IDE 数据面的 WS 劫持。
 *
 * <p>拒绝路径不建立任何反向连接，因此在握手前即返回 HTTP 状态码。
 */
class IdeBrowserWebSocketGatewayTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final String STUDIO_HOST = "studio.test";

  private IdeBrowserWebSocketGateway gateway() {
    return gateway(
        new IdeBrowserSessionRegistry(new SecureRandom(), Clock.systemUTC(), Duration.ofHours(1)));
  }

  private IdeBrowserWebSocketGateway gateway(IdeBrowserSessionRegistry sessions) {
    IdeGatewayProperties properties = new IdeGatewayProperties();
    return new IdeBrowserWebSocketGateway(
        mock(IdeCommandTransport.class),
        new InMemoryIdeStreamTicketService(),
        new IdeStreamPairingRegistry<>(),
        properties,
        sessions,
        mock(ClusterNodeRegistry.class),
        mock(ClusterInternalClient.class),
        new StandardWebSocketClient());
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/environment-ide/" + ENV + "/ws");
    request.setServerName(STUDIO_HOST);
    request.setScheme("http");
    request.setServerPort(80);
    return request;
  }

  @Test
  void rejectsHandshakeWithoutOrigin() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request(), response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeFromSiblingSubdomain() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://evil." + STUDIO_HOST);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithDifferentScheme() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "https://" + STUDIO_HOST);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithDifferentPort() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + STUDIO_HOST + ":8080");
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithCrossSiteSecFetchSite() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + STUDIO_HOST);
    request.addHeader("Sec-Fetch-Site", "cross-site");
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  /** 同源网关无需额外显式配置，但未持有有效浏览器会话时必须以 401 明确拒绝握手。 */
  @Test
  void rejectsHandshakeWithoutValidSession() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + STUDIO_HOST);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(401, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithCookieFromDifferentEnvironment() throws Exception {
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), Clock.systemUTC(), Duration.ofHours(1));
    EnvironmentId foreignEnv = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
    IdeSessionIdentity foreignIdentity =
        new IdeSessionIdentity(foreignEnv, "instance", UUID.randomUUID());
    String foreignView = UUID.randomUUID().toString();
    sessions.viewOpened(foreignIdentity, foreignView, "/managed/workspace");
    String foreignCookie = sessions.open(foreignIdentity, foreignView, "/managed/workspace");

    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + STUDIO_HOST);
    request.setCookies(new Cookie("kk_ide_session", foreignCookie));
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway(sessions).handleUpgrade(ENV, "/ws", request, response);
    assertEquals(401, response.getStatus());
  }
}
