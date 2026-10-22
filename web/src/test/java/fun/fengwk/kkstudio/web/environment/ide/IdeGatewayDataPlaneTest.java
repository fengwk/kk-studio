package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeHeader;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamControlFrame;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 意图：IDE 数据面必须真实地把浏览器 HTTP exchange 经一次性 ticket 与反向连接配对后双向转发（远大于 16MiB 的真实 servlet 流）， 过滤
 * hop-by-hop、在代际/凭据/等待超时上 fail-closed，并用来自 Daemon OPEN_VIEW 元数据的 workspace 完成 bootstrap 跳转。
 */
class IdeGatewayDataPlaneTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String INSTANCE = "33333333-3333-3333-3333-333333333333";
  private static final UUID LEASE = new UUID(0L, 1L);
  private static final DaemonIdeStreamCodec CODEC = new DaemonIdeStreamCodec();

  private static IdeGatewayProperties properties() {
    return new IdeGatewayProperties();
  }

  private static final class Harness {

    final IdeGatewayProperties properties = properties();
    final InMemoryIdeStreamTicketService tickets = new InMemoryIdeStreamTicketService();
    final IdeStreamPairingRegistry<IdeStreamPeer> pairings = new IdeStreamPairingRegistry<>();
    final IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), Clock.systemUTC(), Duration.ofHours(1));
    final IdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();
    final DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, INSTANCE, LEASE);
    final FakeTransport transport = new FakeTransport(pairings);
    final ClusterNodeRegistry clusterNodeRegistry =
        new ClusterNodeRegistry() {
          @Override
          public Optional<ClusterOwnerRoute> route(EnvironmentId environmentId) {
            return Optional.empty();
          }

          @Override
          public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
            return Optional.empty();
          }
        };
    final IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(
            transport,
            tickets,
            pairings,
            properties,
            sessions,
            bootstrap,
            clusterNodeRegistry,
            new IdeRemoteDataPlaneForwarder(
                mock(IdeClusterClient.class), properties, mock(HttpClient.class)));

    Harness() {
      transport.identity = identity;
    }

    String sessionCookie() {
      String view = UUID.randomUUID().toString();
      sessions.viewOpened(identity, view, "/managed/workspace");
      return sessions.open(identity, view, "/managed/workspace");
    }

    MockHttpServletRequest request(String method, String path) {
      MockHttpServletRequest request = new MockHttpServletRequest(method, path);
      request.setServerName(ENV + ".ide.test");
      request.addHeader("Host", ENV + ".ide.test");
      request.setCookies(new Cookie(properties.requireSessionCookieName(), sessionCookie()));
      return request;
    }
  }

  @Test
  void streamsHttpResponseLargerThan16MiBWithHopByHopFiltering() throws Exception {
    Harness harness = new Harness();
    int total = 20 * 1024 * 1024;
    harness.transport.peerFactory =
        () ->
            new ScriptedPeer(
                total,
                List.of(
                    new IdeHeader("content-type", "text/plain"),
                    new IdeHeader("Connection", "keep-alive")));

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/", request, response);

    assertEquals(200, response.getStatus());
    byte[] body = response.getContentAsByteArray();
    assertEquals(total, body.length);
    for (int i = 0; i < body.length; i++) {
      if (body[i] != (byte) i) {
        throw new AssertionError("byte mismatch at " + i);
      }
    }
    assertEquals("text/plain", response.getHeader("content-type"));
    // hop-by-hop header 不得转发给浏览器。
    assertFalse(response.getHeaderNames().contains("Connection"));
    // 正常完成后在途 OPEN_STREAM 恰好被放弃一次（幂等，不重复）。
    assertEquals(1, harness.transport.abandoned.size());
  }

  /** 慢速上游超过一个轮询间隔才产出首字节时必须继续等待，而不是误判连接关闭。 */
  @Test
  void slowUpstreamIdlePollIsNotTreatedAsClosed() throws Exception {
    Harness harness = new Harness();
    ScriptedPeer peer =
        new ScriptedPeer(0, List.of(new IdeHeader("content-type", "text/plain")))
            .withIdlePollsBeforeResponse(8);
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/slow");
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/slow", request, response);

    assertEquals(200, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
  }

  /** 与 Node peer 一致：所有 method（含 GET）都转发请求体，绝不因 method 丢弃 body。 */
  @Test
  void forwardsRequestBodyForGetMethod() throws Exception {
    Harness harness = new Harness();
    ScriptedPeer peer = new ScriptedPeer(0, List.of());
    harness.transport.peerFactory = () -> peer;
    byte[] payload = "get-with-body".getBytes(StandardCharsets.UTF_8);

    MockHttpServletRequest request = harness.request("GET", "/search");
    request.setContent(payload);
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/search", request, response);

    assertEquals(200, response.getStatus());
    assertEquals(payload.length, peer.receivedRequestBytes);
    assertEquals("GET", harness.transport.lastCommand.get().method());
  }

  /** 上游提前响应（不等待大请求体）时双向都不得死锁：响应照常完成。 */
  @Test
  void earlyUpstreamResponseDoesNotDeadlockOnLargeRequestBody() throws Exception {
    Harness harness = new Harness();
    ScriptedPeer peer = new ScriptedPeer(0, List.of(new IdeHeader("content-type", "text/plain")));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("POST", "/upload");
    request.addHeader("Origin", "http://" + ENV + ".ide.test");
    request.setContent(new byte[4 * 1024 * 1024]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/upload", request, response);

    assertEquals(200, response.getStatus());
    // 上游在请求体发完前就结束了 exchange，因此没有把整个请求体交给 Daemon。
    assertTrue(peer.receivedRequestBytes < 4 * 1024 * 1024);
  }

  @Test
  void forwardsRequestBodyToDaemon() throws Exception {
    Harness harness = new Harness();
    ScriptedPeer peer = new ScriptedPeer(0, List.of(new IdeHeader("content-type", "text/plain")));
    harness.transport.peerFactory = () -> peer;
    byte[] payload = "hello-code-server".getBytes(StandardCharsets.UTF_8);

    MockHttpServletRequest request = harness.request("POST", "/api/save");
    request.addHeader("Origin", "http://" + ENV + ".ide.test");
    request.setContent(payload);
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(200, response.getStatus());
    assertEquals(payload.length, peer.receivedRequestBytes);
    assertEquals("/api/save", harness.transport.lastCommand.get().path());
    assertEquals("POST", harness.transport.lastCommand.get().method());
  }

  /** 同站其它子域或跨站的 unsafe 请求必须被拒绝（精确同源，不允许任意 scheme/port）。 */
  @Test
  void rejectsUnsafeRequestFromForeignOrigin() throws Exception {
    Harness harness = new Harness();
    MockHttpServletRequest request = harness.request("POST", "/api/save");
    request.addHeader("Origin", "https://evil." + ENV + ".ide.test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(403, response.getStatus());
  }

  /** 缺 Origin 且无明确同站声明（Sec-Fetch-Site）时 fail-closed。 */
  @Test
  void rejectsUnsafeRequestWithoutOriginAndSecFetchSite() throws Exception {
    Harness harness = new Harness();
    MockHttpServletRequest request = harness.request("POST", "/api/save");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(403, response.getStatus());
  }

  @Test
  void rejectsCrossSiteSecFetchSite() throws Exception {
    Harness harness = new Harness();
    MockHttpServletRequest request = harness.request("POST", "/api/save");
    request.addHeader("Sec-Fetch-Site", "cross-site");
    request.addHeader("Origin", "http://" + ENV + ".ide.test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(403, response.getStatus());
  }

  /** 精确同源（scheme+host+effective port）的 unsafe 请求与同站声明都放行。 */
  @Test
  void allowsExactSameOriginUnsafeRequest() throws Exception {
    Harness harness = new Harness();
    ScriptedPeer peer = new ScriptedPeer(0, List.of());
    harness.transport.peerFactory = () -> peer;
    MockHttpServletRequest request = harness.request("POST", "/api/save");
    request.addHeader("Origin", "http://" + ENV + ".ide.test");
    request.addHeader("Sec-Fetch-Site", "same-origin");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(200, response.getStatus());
  }

  /** 缺 Origin 但声明 same-origin/none（直接输入/同源导航）时按同源处理。 */
  @Test
  void allowsUnsafeRequestWithSameSiteSecFetchSiteOnly() throws Exception {
    Harness harness = new Harness();
    harness.transport.peerFactory = () -> new ScriptedPeer(0, List.of());
    MockHttpServletRequest request = harness.request("POST", "/api/save");
    request.addHeader("Sec-Fetch-Site", "none");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(200, response.getStatus());
  }

  /** OPTIONS 不被代理。 */
  @Test
  void doesNotProxyOptions() throws Exception {
    Harness harness = new Harness();
    MockHttpServletRequest request = harness.request("OPTIONS", "/api/save");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/api/save", request, response);

    assertEquals(403, response.getStatus());
    assertEquals(0, harness.transport.abandoned.size());
  }

  /** 多面板：同一代际下另一个受管 workspace 请求必须放行，未授权的仍拒绝。 */
  @Test
  void allowsAuthorizedSiblingWorkspaceButRejectsUnknownOne() throws Exception {
    Harness harness = new Harness();
    harness.transport.peerFactory = () -> new ScriptedPeer(0, List.of());
    // 第二个面板：同代际的另一个受管 workspace。
    String otherView = UUID.randomUUID().toString();
    harness.sessions.viewOpened(harness.identity, otherView, "/managed/other");
    harness.sessions.open(harness.identity, otherView, "/managed/other");

    MockHttpServletRequest allowed = harness.request("GET", "/");
    allowed.setParameter("workspace", "/managed/other");
    MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/", allowed, allowedResponse);
    assertEquals(200, allowedResponse.getStatus());

    MockHttpServletRequest rejected = harness.request("GET", "/");
    rejected.setParameter("workspace", "/etc");
    MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/", rejected, rejectedResponse);
    assertEquals(403, rejectedResponse.getStatus());
  }

  @Test
  void rejectsRequestWithoutSessionCookie() throws Exception {
    Harness harness = new Harness();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setServerName(ENV + ".ide.test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/", request, response);

    assertEquals(401, response.getStatus());
  }

  @Test
  void rejectsWhenGenerationChanged() throws Exception {
    Harness harness = new Harness();
    String cookie = harness.sessionCookie();
    harness.transport.identity = new DaemonConnectionIdentity(ENV, INSTANCE, UUID.randomUUID());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setCookies(new Cookie(harness.properties.requireSessionCookieName(), cookie));
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/", request, response);

    assertEquals(409, response.getStatus());
  }

  @Test
  void timesOutWhenDaemonNeverConnectsAndAbandonsCommand() throws Exception {
    Harness harness = new Harness();
    harness.properties.setPairingTimeout(Duration.ofMillis(50));
    harness.transport.peerFactory = () -> new ScriptedPeer(0, List.of());
    harness.transport.pairOnRequest = false;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/", request, response);

    assertEquals(504, response.getStatus());
    assertEquals(1, harness.transport.abandoned.size());
    assertEquals(0, harness.pairings.pendingCount());
  }

  @Test
  void bootstrapConsumesTicketAndRedirectsToServerWorkspace() throws Exception {
    Harness harness = new Harness();
    // 真实前置：视图先由 OPEN_VIEW 授权，票据才能被消费。
    String view = UUID.randomUUID().toString();
    harness.sessions.viewOpened(harness.identity, view, "/managed/workspace");
    String ticket = harness.bootstrap.issue(harness.identity, view, "/managed/workspace");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__ide/bootstrap");
    request.setServerName(ENV + ".ide.test");
    request.setQueryString("token=" + ticket);
    request.setParameter("token", ticket);
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleBootstrap(ENV, request, response);

    assertEquals(303, response.getStatus());
    String setCookie = response.getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("HttpOnly"));
    assertTrue(setCookie.contains("SameSite=Strict"));
    assertTrue(setCookie.contains("Path=/environment-ide/" + ENV + "/"));
    assertTrue(setCookie.startsWith("kk_ide_session="));
    assertEquals(
        "/environment-ide/" + ENV + "/?workspace=%2Fmanaged%2Fworkspace",
        response.getHeader("Location"));
  }

  @Test
  void bootstrapCookieContainsSecureWhenRequestIsSecureAndAppliesContextPathToPathAndLocation()
      throws Exception {
    Harness harness = new Harness();
    String view = UUID.randomUUID().toString();
    harness.sessions.viewOpened(harness.identity, view, "/managed/workspace");
    String ticket = harness.bootstrap.issue(harness.identity, view, "/managed/workspace");

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/studio/__ide/bootstrap");
    request.setContextPath("/studio");
    request.setSecure(true);
    request.setQueryString("token=" + ticket);
    request.setParameter("token", ticket);
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleBootstrap(ENV, request, response);

    assertEquals(303, response.getStatus());
    String setCookie = response.getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("Path=/studio/environment-ide/" + ENV + "/"));
    assertTrue(setCookie.contains("; Secure"));
    assertEquals(
        "/studio/environment-ide/" + ENV + "/?workspace=%2Fmanaged%2Fworkspace",
        response.getHeader("Location"));
  }

  @Test
  void bootstrapCookieOmitsSecureWhenRequestIsNotSecure() throws Exception {
    Harness harness = new Harness();
    String view = UUID.randomUUID().toString();
    harness.sessions.viewOpened(harness.identity, view, "/managed/workspace");
    String ticket = harness.bootstrap.issue(harness.identity, view, "/managed/workspace");

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__ide/bootstrap");
    request.setSecure(false);
    request.setQueryString("token=" + ticket);
    request.setParameter("token", ticket);
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleBootstrap(ENV, request, response);

    assertEquals(303, response.getStatus());
    String setCookie = response.getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertFalse(setCookie.contains("; Secure"));
  }

  @Test
  void rejectsSessionCookieFromDifferentEnvironment() throws Exception {
    Harness harness = new Harness();
    EnvironmentId foreignEnv = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
    DaemonConnectionIdentity foreignIdentity =
        new DaemonConnectionIdentity(foreignEnv, "instance", UUID.randomUUID());
    String foreignView = UUID.randomUUID().toString();
    harness.sessions.viewOpened(foreignIdentity, foreignView, "/managed/workspace");
    String foreignCookie =
        harness.sessions.open(foreignIdentity, foreignView, "/managed/workspace");

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setCookies(new Cookie(harness.properties.requireSessionCookieName(), foreignCookie));
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleHttp(ENV, "/", request, response);

    assertEquals(401, response.getStatus());
  }

  /** 视图关闭后旧票据必须失效（410），且绝不因此重新授权视图。 */
  @Test
  void bootstrapRejectsTicketOfClosedView() throws Exception {
    Harness harness = new Harness();
    String view = UUID.randomUUID().toString();
    harness.sessions.viewOpened(harness.identity, view, "/managed/workspace");
    String ticket = harness.bootstrap.issue(harness.identity, view, "/managed/workspace");
    harness.sessions.viewClosed(harness.identity, view);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__ide/bootstrap");
    request.setParameter("token", ticket);
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleBootstrap(ENV, request, response);

    assertEquals(410, response.getStatus());
    assertNull(response.getHeader("Set-Cookie"));
    assertEquals(0, harness.sessions.viewCount(harness.identity));
  }

  @Test
  void bootstrapRejectsUnknownTicket() throws Exception {
    Harness harness = new Harness();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__ide/bootstrap");
    request.setParameter("token", "unknown");
    MockHttpServletResponse response = new MockHttpServletResponse();

    harness.dataPlane.handleBootstrap(ENV, request, response);

    assertEquals(410, response.getStatus());
  }

  /** 由 streamUrl 生成：必须在受控 stream endpoint 前缀下，携带一次性 ticket 与 environment 查询，不泄露注册 token。 */
  @Test
  void streamUrlCarriesTicketUnderControlledPath() {
    IdeGatewayProperties properties = properties();
    assertEquals(
        "/api/harness/environment-stream/v1/abc?environment=" + ENV,
        properties.streamUrl(ENV, "abc"));
  }

  @Test
  void rebaseUpstreamRedirectLocationKeepsRouteBase() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/environment-ide/" + ENV + "/");
    request.setContextPath("/my-studio");
    assertEquals(
        "/my-studio/environment-ide/" + ENV + "/login?a=1",
        IdeGatewayDataPlane.rebaseLocation("/login?a=1", request, ENV));
    assertEquals(
        "/my-studio/environment-ide/" + ENV + "/?workspace=x",
        IdeGatewayDataPlane.rebaseLocation("/?workspace=x", request, ENV));
    // 外部绝对 URL 或相对路径不重写
    assertEquals(
        "https://example.com/external",
        IdeGatewayDataPlane.rebaseLocation("https://example.com/external", request, ENV));
    assertEquals("subpath/foo", IdeGatewayDataPlane.rebaseLocation("subpath/foo", request, ENV));
  }

  /**
   * 传输在响应已经入队之后关闭（如 code-server 发送完整压缩响应后立即断开）：网关仍必须把队列中剩余的 HTTP_RESPONSE/body/END 交付， 对已关闭 peer
   * 发送消费 ACK 不得中断成交付，最终得到原始 200 与原始压缩字节。
   */
  @Test
  void completesCompressedResponseAfterTransportClosed() throws Exception {
    Harness harness = new Harness();
    byte[] plain = "hello-ide-compressed-body".getBytes(StandardCharsets.UTF_8);
    byte[] compressed = gzip(plain);
    TransportClosedPeer peer =
        new TransportClosedPeer(
            List.of(
                new IdeStreamPeer.Inbound.Text(
                    CODEC.encodeHttpResponse(
                        new IdeStreamControlFrame.HttpResponse(
                            200, List.of(new IdeHeader("content-encoding", "gzip"))))),
                new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpBody(compressed)),
                new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpEnd())));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    harness.dataPlane.handleHttp(ENV, "/", request, response);

    assertEquals(200, response.getStatus());
    assertEquals("gzip", response.getHeader("content-encoding"));
    assertArrayEquals(compressed, response.getContentAsByteArray());
    assertArrayEquals(plain, gunzip(response.getContentAsByteArray()));
    // 关闭后的消费 ACK 必须被丢弃（counted 但绝不抛出），数据交付照常完成。
    assertTrue(peer.acknowledgements.get() > 0);
  }

  /** 传输在 HTTP_END 之前关闭：响应不完整，必须以 IOException 失败，不得伪造成功。 */
  @Test
  void incompleteResponseBeforeEndFailsWithIoException() throws Exception {
    Harness harness = new Harness();
    byte[] plain = "truncated-compressed-body".getBytes(StandardCharsets.UTF_8);
    byte[] compressed = gzip(plain);
    TransportClosedPeer peer =
        new TransportClosedPeer(
            List.of(
                new IdeStreamPeer.Inbound.Text(
                    CODEC.encodeHttpResponse(
                        new IdeStreamControlFrame.HttpResponse(
                            200,
                            List.of(
                                new IdeHeader("content-encoding", "gzip"),
                                new IdeHeader("etag", "\"v1\""),
                                new IdeHeader("location", "/proxy/xxx"))))),
                new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpBody(compressed))));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThrows(
        IOException.class, () -> harness.dataPlane.handleHttp(ENV, "/", request, response));

    // 未提交的错误响应必须被 reset：不得残留上游 Content-Encoding/ETag/Location，也不得留下半截压缩体。
    assertNull(response.getHeader("content-encoding"));
    assertNull(response.getHeader("etag"));
    assertNull(response.getHeader("location"));
    assertEquals(0, response.getContentAsByteArray().length);
  }

  /** 已提交的响应无法回滚：仍以异常中止截断，绝不把残留压缩字节当作完整成功。 */
  @Test
  void committedResponseIsNotResetOnFailure() throws Exception {
    Harness harness = new Harness();
    byte[] compressed = gzip("committed-body".getBytes(StandardCharsets.UTF_8));
    TransportClosedPeer peer =
        new TransportClosedPeer(
            List.of(
                new IdeStreamPeer.Inbound.Text(
                    CODEC.encodeHttpResponse(
                        new IdeStreamControlFrame.HttpResponse(
                            200, List.of(new IdeHeader("content-encoding", "gzip"))))),
                new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpBody(compressed))));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    assertThrows(
        IOException.class, () -> harness.dataPlane.handleHttp(ENV, "/", request, response));
    // 已提交响应不能被 reset：上游 Content-Encoding 保留（无法回滚），但同时也没有被伪装成功。
    assertEquals("gzip", response.getHeader("content-encoding"));
  }

  /** 未先收到 HTTP_RESPONSE 的 HTTP_END 是协议错误：不得默认成 200 空 body，未提交响应必须被 reset。 */
  @Test
  void endBeforeResponseFailsAndResetsUncommittedResponse() {
    Harness harness = new Harness();
    TransportClosedPeer peer =
        new TransportClosedPeer(List.of(new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpEnd())));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    // 模拟上一层已写入的残留头：失败时必须被 reset 清除，而不是被当成成功的 200 空 body。
    response.addHeader("content-encoding", "br");
    assertThrows(
        IOException.class, () -> harness.dataPlane.handleHttp(ENV, "/", request, response));
    assertNull(response.getHeader("content-encoding"));
    assertEquals(0, response.getContentAsByteArray().length);
  }

  /** 重复 HTTP_RESPONSE 是协议错误：不得静默覆盖状态/headers，必须以失败终止并清空未提交响应。 */
  @Test
  void duplicateResponseFailsAndClearsHeaders() {
    Harness harness = new Harness();
    TransportClosedPeer peer =
        new TransportClosedPeer(
            List.of(
                new IdeStreamPeer.Inbound.Text(
                    CODEC.encodeHttpResponse(
                        new IdeStreamControlFrame.HttpResponse(
                            200,
                            List.of(
                                new IdeHeader("content-encoding", "gzip"),
                                new IdeHeader("etag", "\"v1\""))))),
                new IdeStreamPeer.Inbound.Text(
                    CODEC.encodeHttpResponse(
                        new IdeStreamControlFrame.HttpResponse(
                            500, List.of(new IdeHeader("content-encoding", "br"))))),
                new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpEnd())));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThrows(
        IOException.class, () -> harness.dataPlane.handleHttp(ENV, "/", request, response));
    // 第二次响应不得覆盖：状态退回默认且所有残留 headers 被清空。
    assertEquals(200, response.getStatus());
    assertNull(response.getHeader("content-encoding"));
    assertNull(response.getHeader("etag"));
  }

  /** 未先收到 HTTP_RESPONSE 的 HTTP body 是协议错误：不得写入响应，必须以失败终止。 */
  @Test
  void bodyBeforeResponseFails() {
    Harness harness = new Harness();
    TransportClosedPeer peer =
        new TransportClosedPeer(
            List.of(new IdeStreamPeer.Inbound.Binary(CODEC.encodeHttpBody(new byte[] {1, 2, 3}))));
    harness.transport.peerFactory = () -> peer;

    MockHttpServletRequest request = harness.request("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThrows(
        IOException.class, () -> harness.dataPlane.handleHttp(ENV, "/", request, response));
    assertEquals(0, response.getContentAsByteArray().length);
  }

  private static byte[] gzip(byte[] payload) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(payload);
    }
    return bytes.toByteArray();
  }

  private static byte[] gunzip(byte[] payload) throws IOException {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
      return gzip.readAllBytes();
    }
  }

  /** 可编程 IDE 命令传输：OPEN_STREAM 时把脚本化 peer 交付给配对注册表。 */
  private static final class FakeTransport implements IdeCommandTransport {

    final IdeStreamPairingRegistry<IdeStreamPeer> pairings;
    volatile DaemonConnectionIdentity identity;
    volatile Supplier<IdeStreamPeer> peerFactory = () -> new ScriptedPeer(0, List.of());
    volatile boolean pairOnRequest = true;
    final AtomicReference<IdeCommand> lastCommand = new AtomicReference<>();
    final List<UUID> abandoned = new CopyOnWriteArrayList<>();

    private FakeTransport(IdeStreamPairingRegistry<IdeStreamPeer> pairings) {
      this.pairings = pairings;
    }

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      lastCommand.set(command);
      if (pairOnRequest && command.action() == IdeAction.OPEN_STREAM) {
        pairings.pair(command.ticket(), peerFactory.get());
      }
      return new IdeRequest(
          identity,
          UUID.randomUUID(),
          CompletableFuture.completedFuture(IdeReply.ok(null, null, null)));
    }

    @Override
    public void abandonIde(UUID invocationId) {
      abandoned.add(invocationId);
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(identity);
    }
  }

  /** 按需生成响应的脚本化反向连接：HTTP_RESPONSE → byte1 分片 → byte2 END，内容为可校验的递增字节。 */
  private static final class ScriptedPeer implements IdeStreamPeer {

    private final int bodyBytes;
    private final List<IdeHeader> headers;
    private int idlePollsBeforeResponse;
    private int idlePolls;
    private boolean responseSent;
    private int sentBytes;
    private boolean ended;
    private volatile boolean closed;
    private volatile int receivedRequestBytes;

    private ScriptedPeer(int bodyBytes, List<IdeHeader> headers) {
      this.bodyBytes = bodyBytes;
      this.headers = headers;
    }

    @Override
    public void sendText(String frame) {
      // HTTP 模式不发送文本帧。
    }

    @Override
    public void sendBinary(byte[] frame) {
      if (frame.length > 0 && frame[0] == 1) {
        receivedRequestBytes += frame.length - 1;
      }
    }

    @Override
    public void sendAcknowledgement(byte[] frame) {
      // HTTP 模式下的消费 ACK：本脚本 peer 不需要据此推进。
    }

    @Override
    public boolean closed() {
      return closed;
    }

    ScriptedPeer withIdlePollsBeforeResponse(int idlePollsBeforeResponse) {
      this.idlePollsBeforeResponse = idlePollsBeforeResponse;
      return this;
    }

    @Override
    public Inbound poll(Duration timeout) {
      if (closed) {
        return null;
      }
      if (idlePolls < idlePollsBeforeResponse) {
        // 空闲：返回 null 但不是关闭；数据面必须继续等到真实 deadline。
        idlePolls++;
        return null;
      }
      if (!responseSent) {
        responseSent = true;
        return new Inbound.Text(
            CODEC.encodeHttpResponse(new IdeStreamControlFrame.HttpResponse(200, headers)));
      }
      if (sentBytes < bodyBytes) {
        int length = Math.min(DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES, bodyBytes - sentBytes);
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
          payload[i] = (byte) (sentBytes + i);
        }
        sentBytes += length;
        return new Inbound.Binary(CODEC.encodeHttpBody(payload));
      }
      if (!ended) {
        ended = true;
        return new Inbound.Binary(CODEC.encodeHttpEnd());
      }
      return null;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /**
   * 模拟「请求已发出后传输关闭、但入站队列仍保留待交付响应」的 Daemon 反向连接（与 {@link WebSocketIdeStreamPeer} 的 {@code
   * onTransportClosed} 语义一致）。
   *
   * <p>首次轮询即标记传输关闭，随后逐个交付脚本化帧；数据发送（请求体/END）由调用方在关闭前完成，关闭后的消费 ACK 必须被丢弃而不是抛出。
   */
  private static final class TransportClosedPeer implements IdeStreamPeer {

    private final List<Inbound> frames;
    private final AtomicInteger acknowledgements = new AtomicInteger();
    private int index;
    private volatile boolean closed;

    private TransportClosedPeer(List<Inbound> frames) {
      this.frames = frames;
    }

    @Override
    public void sendText(String frame) {
      // HTTP 模式下的响应读取阶段不再发送文本帧。
    }

    @Override
    public void sendBinary(byte[] frame) {
      if (closed) {
        throw new IllegalStateException("reverse stream peer is closed");
      }
    }

    @Override
    public void sendAcknowledgement(byte[] frame) {
      // 关闭后的消费 ACK 可安全丢弃：只计数，绝不抛出。
      acknowledgements.incrementAndGet();
    }

    @Override
    public Inbound poll(Duration timeout) {
      if (index == 0) {
        // 请求体与 END 已经发出；此刻传输关闭，但响应仍在入站队列中等待交付。
        closed = true;
      }
      if (index < frames.size()) {
        return frames.get(index++);
      }
      return null;
    }

    @Override
    public boolean closed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
