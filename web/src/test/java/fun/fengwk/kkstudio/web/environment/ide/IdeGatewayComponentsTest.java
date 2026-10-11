package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图：IDE Origin 识别必须严格（只有模板形状的 Host 才算 IDE Origin），浏览器会话必须绑定代际且随租约关闭而失效，且 IDE Origin
 * 请求必须由数据面本地处理、绝不进入 Studio REST 管线。
 */
class IdeGatewayComponentsTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static IdeOriginProperties originProperties() {
    IdeOriginProperties properties = new IdeOriginProperties();
    properties.setOriginTemplate("https://{environmentId}.ide.test");
    properties.setBootstrapPath("/__ide/bootstrap");
    return properties;
  }

  @Test
  void hostResolverOnlyMatchesIdeOriginTemplate() {
    IdeHostResolver resolver = new IdeHostResolver(originProperties());

    assertEquals(Optional.of(ENV), resolver.resolve(ENV + ".ide.test"));
    assertEquals(Optional.of(ENV), resolver.resolve(ENV + ".ide.test:8443"));
    // 非 IDE Origin / 未知子域 / 非法 UUID / 大小写变体都不匹配。
    assertTrue(resolver.resolve("studio.test").isEmpty());
    assertTrue(resolver.resolve("www.ide.test").isEmpty());
    assertTrue(resolver.resolve("not-a-uuid.ide.test").isEmpty());
    assertEquals(Optional.of(ENV), resolver.resolve(ENV.toString().toUpperCase() + ".ide.test"));
    assertTrue(resolver.resolve(null).isEmpty());
  }

  @Test
  void browserSessionBindsGenerationAndClosesWithLease() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(5));
    IdeSessionIdentity identity = new IdeSessionIdentity(ENV, "instance", UUID.randomUUID());

    // 未知 cookie 必须是「未命中」而不是 NPE。
    assertTrue(sessions.lookup("unknown-cookie").isEmpty());
    String cookie = sessions.open(identity, "/managed/workspace");
    assertTrue(sessions.lookup(cookie).orElseThrow().authorizesWorkspace("/managed/workspace"));
    assertFalse(sessions.lookup(cookie).orElseThrow().authorizesWorkspace("/other"));

    // 过期即失效。
    clock.advance(Duration.ofMinutes(6));
    assertTrue(sessions.lookup(cookie).isEmpty());

    // 租约关闭按代际清除。
    String other = sessions.open(identity, "/managed/workspace");
    sessions.closeGeneration(ENV, UUID.randomUUID());
    assertFalse(sessions.lookup(other).isEmpty());
    sessions.closeGeneration(ENV, identity.leaseToken());
    assertTrue(sessions.lookup(other).isEmpty());
  }

  /** 多面板：同一代际下新授权的 workspace 对已存在的 cookie 立即可用，且按 TTL 淘汰。 */
  @Test
  void browserSessionAuthorizesSiblingViewsPerGeneration() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(
            new SecureRandom(), clock, Duration.ofMinutes(30), Duration.ofMinutes(5));
    IdeSessionIdentity identity = new IdeSessionIdentity(ENV, "instance", UUID.randomUUID());

    String first = sessions.open(identity, "/managed/one");
    String second = sessions.open(identity, "/managed/two");
    assertTrue(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/two"));
    assertTrue(sessions.lookup(second).orElseThrow().authorizesWorkspace("/managed/one"));
    // 别的代际不可见。
    IdeSessionIdentity otherGeneration = new IdeSessionIdentity(ENV, "instance", UUID.randomUUID());
    String otherCookie = sessions.open(otherGeneration, "/managed/three");
    assertFalse(sessions.lookup(otherCookie).orElseThrow().authorizesWorkspace("/managed/one"));

    // 视图 TTL 到期后授权被淘汰（会话本身仍在有效期内）。
    clock.advance(Duration.ofMinutes(6));
    assertFalse(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/one"));
    assertFalse(sessions.lookup(first).orElseThrow().authorizesWorkspace("/managed/two"));
    assertFalse(sessions.lookup(first).isEmpty());
  }

  @Test
  void ideOriginRequestsAreHandledLocallyAndNeverChained() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeOriginRequestFilter filter =
        new IdeOriginRequestFilter(
            new IdeHostResolver(originProperties()),
            originProperties(),
            dataPlane,
            mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/vscode/workbench.js");
    request.addHeader("Host", ENV + ".ide.test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verify(dataPlane).handleHttp(eq(ENV), eq("/vscode/workbench.js"), any(), any());
    verify(dataPlane, never()).handleBootstrap(any(), any(), any());
    assertNull(chain.getRequest());
  }

  @Test
  void ideOriginBootstrapPathIsHandledAsBootstrap() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeOriginRequestFilter filter =
        new IdeOriginRequestFilter(
            new IdeHostResolver(originProperties()),
            originProperties(),
            dataPlane,
            mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__ide/bootstrap");
    request.addHeader("Host", ENV + ".ide.test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    verify(dataPlane).handleBootstrap(eq(ENV), any(), any());
    verify(dataPlane, never()).handleHttp(any(), any(), any(), any());
  }

  @Test
  void studioOriginRequestsPassThroughToTheChain() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeOriginRequestFilter filter =
        new IdeOriginRequestFilter(
            new IdeHostResolver(originProperties()),
            originProperties(),
            dataPlane,
            mock(IdeBrowserWebSocketGateway.class));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/harness/environments");
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
