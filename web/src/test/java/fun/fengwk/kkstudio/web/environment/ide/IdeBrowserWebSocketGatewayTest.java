package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/**
 * 浏览器 WS 握手的同源准入：必须精确匹配 IDE Origin，堵住同站其它子域/跨站脚本对 IDE 数据面的 WS 劫持。
 *
 * <p>拒绝路径不建立任何反向连接，因此在握手前即返回 HTTP 状态码。
 */
class IdeBrowserWebSocketGatewayTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final String IDE_HOST = ENV + ".ide.test";

  private IdeBrowserWebSocketGateway gateway() {
    IdeGatewayProperties properties = new IdeGatewayProperties();
    properties.setStreamUrlTemplate("wss://gateway.test" + IdeGatewayProperties.STREAM_PATH);
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), Clock.systemUTC(), Duration.ofHours(1));
    return new IdeBrowserWebSocketGateway(
        mock(IdeCommandTransport.class),
        new InMemoryIdeStreamTicketService(),
        new IdeStreamPairingRegistry<>(),
        properties,
        sessions,
        mock(ClusterNodeRegistry.class),
        mock(ClusterInternalClient.class));
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
    request.setServerName(IDE_HOST);
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
    request.addHeader("Origin", "http://evil." + IDE_HOST);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithDifferentScheme() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "https://" + IDE_HOST);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithDifferentPort() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + IDE_HOST + ":8080");
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsHandshakeWithCrossSiteSecFetchSite() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + IDE_HOST);
    request.addHeader("Sec-Fetch-Site", "cross-site");
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway().handleUpgrade(ENV, "/ws", request, response);
    assertEquals(403, response.getStatus());
  }

  /** 未配置数据面时明确不可用，绝不回退。 */
  @Test
  void rejectsWhenGatewayNotConfigured() throws Exception {
    IdeGatewayProperties properties = new IdeGatewayProperties();
    IdeBrowserWebSocketGateway gateway =
        new IdeBrowserWebSocketGateway(
            mock(IdeCommandTransport.class),
            new InMemoryIdeStreamTicketService(),
            new IdeStreamPairingRegistry<>(),
            properties,
            new IdeBrowserSessionRegistry(
                new SecureRandom(), Clock.systemUTC(), Duration.ofHours(1)),
            mock(ClusterNodeRegistry.class),
            mock(ClusterInternalClient.class));
    MockHttpServletRequest request = request();
    request.addHeader("Origin", "http://" + IDE_HOST);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateway.handleUpgrade(ENV, "/ws", request, response);
    assertEquals(503, response.getStatus());
  }
}
