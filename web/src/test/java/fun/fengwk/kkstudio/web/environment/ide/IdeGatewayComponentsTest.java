package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 意图：IDE 同源网关路由（/environment-ide/{environmentId}/...）必须严格校验环境标识，浏览器会话必须绑定代际且随租约关闭而失效，且 IDE
 * 请求必须由数据面本地处理、绝不进入 Studio REST 管线。
 */
class IdeGatewayComponentsTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  @Test
  void browserSessionBindsGenerationAndClosesWithLease() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(5));
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "instance");
    String view = UUID.randomUUID().toString();

    // 未知 cookie 必须是「未命中」而不是 NPE。
    assertTrue(sessions.lookup("unknown-cookie").isEmpty());
    sessions.viewOpened(identity, view, "/managed/workspace");
    String cookie = sessions.open(identity, view, "/managed/workspace");
    assertTrue(sessions.lookup(cookie).orElseThrow().authorizesWorkspace("/managed/workspace"));
    assertFalse(sessions.lookup(cookie).orElseThrow().authorizesWorkspace("/other"));

    // cookie 过期只作废凭据：仍然打开着的视图授权不受影响（无空闲自动销毁）。
    clock.advance(Duration.ofMinutes(6));
    assertTrue(sessions.lookup(cookie).isEmpty());
    assertEquals(1, sessions.viewCount(identity), "expired cookie must not destroy an open view");
    String refreshed = sessions.open(identity, view, "/managed/workspace");
    assertFalse(refreshed.equals(cookie), "expired cookie must be replaced");
    assertTrue(sessions.lookup(refreshed).orElseThrow().authorizesWorkspace("/managed/workspace"));

    // 代际只由 Daemon 实例决定：关闭其它实例的代际不影响本代际，关闭本代际清除 cookie 与授权。
    sessions.closeGeneration(new DaemonConnectionIdentity(ENV, "another-instance"));
    assertFalse(sessions.lookup(refreshed).isEmpty());
    sessions.closeGeneration(identity);
    assertTrue(sessions.lookup(refreshed).isEmpty());
    assertEquals(0, sessions.viewCount(identity));
  }

  /** 多面板：同一代际复用同一 cookie，新授权 workspace 对已存在的 cookie 立即可用，CLOSE_VIEW 真实撤销。 */
  @Test
  void browserSessionSharesCookieAndRevokesClosedView() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(30));
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "instance");

    String firstView = UUID.randomUUID().toString();
    String secondView = UUID.randomUUID().toString();
    sessions.viewOpened(identity, firstView, "/managed/one");
    sessions.viewOpened(identity, secondView, "/managed/two");
    String first = sessions.open(identity, firstView, "/managed/one");
    String second = sessions.open(identity, secondView, "/managed/two");
    // 同一代际只保留一个 cookie：多次 bootstrap 不互相覆盖。
    assertEquals(first, second);
    assertEquals(1, sessions.size());
    assertTrue(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/two"));
    assertTrue(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/one"));

    // 别的代际（另一个 Daemon 实例）不可见。
    DaemonConnectionIdentity otherGeneration = new DaemonConnectionIdentity(ENV, "other-instance");
    String otherView = UUID.randomUUID().toString();
    sessions.viewOpened(otherGeneration, otherView, "/managed/three");
    String otherCookie = sessions.open(otherGeneration, otherView, "/managed/three");
    assertFalse(sessions.lookup(otherCookie).orElseThrow().authorizesWorkspace("/managed/one"));

    // CLOSE_VIEW 按真实视图事件撤销授权，而不是等待时间推断。
    sessions.viewClosed(identity, firstView);
    assertFalse(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/one"));
    assertTrue(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/two"));

    // owner 侧 OPEN_VIEW 事件先于 bootstrap 也能授权。
    String thirdView = UUID.randomUUID().toString();
    sessions.viewOpened(identity, thirdView, "/managed/three");
    assertTrue(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/three"));
  }

  /** bootstrap 只校验真实视图授权，绝不新增授权：否则已关闭视图的旧票据可以复活视图。 */
  @Test
  void bootstrapConsumptionNeverAuthorizesByItself() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(30));
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "instance");
    String view = UUID.randomUUID().toString();

    // 未经 OPEN_VIEW 的票据不得建立任何会话。
    assertThrows(IllegalStateException.class, () -> sessions.open(identity, view, "/managed/one"));
    // 视图已授权但 ticket 的 workspace 与授权不一致时同样拒绝。
    sessions.viewOpened(identity, view, "/managed/one");
    assertThrows(IllegalStateException.class, () -> sessions.open(identity, view, "/managed/two"));
    String cookie = sessions.open(identity, view, "/managed/one");
    assertTrue(sessions.lookup(cookie).isPresent());

    // 视图关闭后旧 ticket 不得复活视图（授权不被重新创建）。
    sessions.viewClosed(identity, view);
    assertThrows(IllegalStateException.class, () -> sessions.open(identity, view, "/managed/one"));
    assertTrue(sessions.lookup(cookie).orElseThrow().authorizedWorkspaces().isEmpty());
  }

  /** 并发 bootstrap/OPEN_VIEW 必须落在同一代际与同一 cookie 上，既不丢视图也不产生第二个 cookie。 */
  @Test
  void concurrentOpensShareOneGenerationAndCookie() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(30));
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "instance");
    int views = 32;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int index = 0; index < views; index++) {
        String view = UUID.randomUUID().toString();
        String workspace = "/managed/" + index;
        results.add(
            pool.submit(
                () -> {
                  sessions.viewOpened(identity, view, workspace);
                  return sessions.open(identity, view, workspace);
                }));
      }
      Set<String> cookies = new HashSet<>();
      for (Future<String> result : results) {
        cookies.add(result.get(30, TimeUnit.SECONDS));
      }
      assertEquals(1, cookies.size(), "one generation must keep exactly one cookie");
      assertEquals(views, sessions.viewCount(identity), "no authorized view may be lost");
      assertEquals(1, sessions.size());
    } finally {
      pool.shutdownNow();
    }
  }

  /** 视图/代际额度耗尽必须显式失败（可在 Native 副作用之前预检），绝不静默伪成功。 */
  @Test
  void capacityLimitsFailExplicitly() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(30));
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "instance");
    for (int index = 0; index < IdeBrowserSessionRegistry.MAX_VIEWS_PER_GENERATION; index++) {
      sessions.viewOpened(identity, UUID.randomUUID().toString(), "/managed/" + index);
    }
    assertThrows(
        IllegalStateException.class,
        () -> sessions.viewOpened(identity, UUID.randomUUID().toString(), "/managed/overflow"));
    assertThrows(IllegalStateException.class, () -> sessions.ensureViewCapacity(ENV));
    // 已授权的 viewId 仍可重复上报（幂等），不因上界而失败。
    assertEquals(IdeBrowserSessionRegistry.MAX_VIEWS_PER_GENERATION, sessions.viewCount(identity));
  }

  @Test
  void idePathRequestsAreHandledLocallyAndPrefixStripped() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter =
        new IdeGatewayFilter(dataPlane, mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/environment-ide/" + ENV + "/vscode/workbench.js");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verify(dataPlane).handleHttp(eq(ENV), eq("/vscode/workbench.js"), any(), any());
    verify(dataPlane, never()).handleBootstrap(any(), any(), any(), any());
    assertNull(chain.getRequest());
  }

  @Test
  void idePathBootstrapIsHandledAsBootstrap() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter =
        new IdeGatewayFilter(dataPlane, mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/environment-ide/" + ENV + "/__ide/bootstrap");
    request.setQueryString("token=xyz");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    verify(dataPlane).handleBootstrap(eq(ENV), eq("/__ide/bootstrap?token=xyz"), any(), any());
    verify(dataPlane, never()).handleHttp(any(), any(), any(), any());
  }

  @Test
  void idePathWithoutTrailingSlashRedirects() throws Exception {
    IdeGatewayFilter filter =
        new IdeGatewayFilter(
            mock(IdeGatewayDataPlane.class), mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/environment-ide/" + ENV);
    request.setQueryString("a=b");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(301, response.getStatus());
    assertEquals("/environment-ide/" + ENV + "/?a=b", response.getHeader("Location"));
  }

  @Test
  void invalidIdePathReturnsNotFound() throws Exception {
    IdeGatewayFilter filter =
        new IdeGatewayFilter(
            mock(IdeGatewayDataPlane.class), mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/environment-ide/not-a-uuid/test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(404, response.getStatus());
  }

  @Test
  void ideFilterWithContextPathStripsRouteBaseOnlyOnceForBootstrapResourceAndWebSocket()
      throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeBrowserWebSocketGateway browserGateway = mock(IdeBrowserWebSocketGateway.class);
    IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane, browserGateway);

    // 1. bootstrap: /studio/environment-ide/{ENV}/__ide/bootstrap?token=test-tok -> remainder is
    // /__ide/bootstrap
    MockHttpServletRequest bootstrapReq =
        new MockHttpServletRequest("GET", "/studio/environment-ide/" + ENV + "/__ide/bootstrap");
    bootstrapReq.setContextPath("/studio");
    bootstrapReq.setQueryString("token=test-tok");
    MockHttpServletResponse bootstrapResp = new MockHttpServletResponse();
    filter.doFilter(bootstrapReq, bootstrapResp, new MockFilterChain());
    verify(dataPlane).handleBootstrap(eq(ENV), eq("/__ide/bootstrap?token=test-tok"), any(), any());

    // 2. resource whose path contains a nested /environment-ide/: strip prefix only once
    MockHttpServletRequest resourceReq =
        new MockHttpServletRequest(
            "GET", "/studio/environment-ide/" + ENV + "/environment-ide/main.js");
    resourceReq.setContextPath("/studio");
    resourceReq.setQueryString("v=1");
    MockHttpServletResponse resourceResp = new MockHttpServletResponse();
    filter.doFilter(resourceReq, resourceResp, new MockFilterChain());
    verify(dataPlane).handleHttp(eq(ENV), eq("/environment-ide/main.js?v=1"), any(), any());

    // 3. web socket upgrade: strip prefix only once
    MockHttpServletRequest wsReq =
        new MockHttpServletRequest("GET", "/studio/environment-ide/" + ENV + "/ws/channel");
    wsReq.setContextPath("/studio");
    wsReq.addHeader("Upgrade", "websocket");
    wsReq.addHeader("Connection", "Upgrade");
    MockHttpServletResponse wsResp = new MockHttpServletResponse();
    filter.doFilter(wsReq, wsResp, new MockFilterChain());
    verify(browserGateway).handleUpgrade(eq(ENV), eq("/ws/channel"), any(), any());

    // 4. missing trailing slash redirect preserves contextPath
    MockHttpServletRequest redirectReq =
        new MockHttpServletRequest("GET", "/studio/environment-ide/" + ENV);
    redirectReq.setContextPath("/studio");
    redirectReq.setQueryString("k=v");
    MockHttpServletResponse redirectResp = new MockHttpServletResponse();
    filter.doFilter(redirectReq, redirectResp, new MockFilterChain());
    assertEquals(301, redirectResp.getStatus());
    assertEquals("/studio/environment-ide/" + ENV + "/?k=v", redirectResp.getHeader("Location"));
  }

  @Test
  void nonIdeRequestsPassThroughToTheChain() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter =
        new IdeGatewayFilter(dataPlane, mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/environments");
    request.addHeader("Host", "studio.test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verify(dataPlane, never()).handleHttp(any(), any(), any(), any());
    assertSame(request, chain.getRequest());
  }

  /** 可推进的 UTC 时钟：确定性验证会话过期。 */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
