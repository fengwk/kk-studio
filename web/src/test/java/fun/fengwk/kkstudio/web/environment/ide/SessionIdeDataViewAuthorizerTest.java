package fun.fengwk.kkstudio.web.environment.ide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

class SessionIdeDataViewAuthorizerTest {
  @Test
  void expiredAndRevokedGenerationCookiesStayUnauthorized() {
    Clock clock = mock(Clock.class);
    Instant now = Instant.parse("2026-10-28T00:00:00Z");
    when(clock.instant()).thenReturn(now);
    IdeBrowserSessionRegistry sessions =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofSeconds(10));
    EnvironmentId env = EnvironmentId.parse("55555555-5555-5555-5555-555555555555");
    UUID view = UUID.randomUUID();
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(env, "test");
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(env)).thenReturn(Optional.of(identity));
    IdeDataViewAuthorizer auth =
        new SessionIdeDataViewAuthorizer(sessions, new IdeGatewayProperties(), transport);
    sessions.viewOpened(identity, view.toString(), 1, "/workspace");
    String cookie = "kk_ide_session=" + sessions.open(identity, view.toString(), 1, "/workspace");
    assertThat(auth.authorize(env, view, cookie)).isPresent();
    when(clock.instant()).thenReturn(now.plusSeconds(10));
    assertThat(auth.authorize(env, view, cookie)).isEmpty();
    String fresh = "kk_ide_session=" + sessions.open(identity, view.toString(), 1, "/workspace");
    sessions.closeGeneration(identity);
    assertThat(auth.authorize(env, view, fresh)).isEmpty();
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackageClasses = IdeDataViewAuthorizer.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = IdeDataViewAuthorizer.class))
  static class ProductionScan {}

  @Test
  void productionBeanUsesRealSessionAndGenerationFences() {
    IdeGatewayProperties properties = new IdeGatewayProperties();
    properties.setSessionCookieName("custom_ide");
    IdeBrowserSessionRegistry sessions = new IdeBrowserSessionRegistry(properties);
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    EnvironmentId env = EnvironmentId.parse("55555555-5555-5555-5555-555555555555");
    UUID view = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(env, "original");
    when(transport.connectionIdentity(env)).thenReturn(Optional.of(identity));
    sessions.viewOpened(identity, view.toString(), 1, "/workspace");
    String token = sessions.open(identity, view.toString(), 1, "/workspace");
    new ApplicationContextRunner()
        .withUserConfiguration(ProductionScan.class)
        .withBean(IdeGatewayProperties.class, () -> properties)
        .withBean(IdeBrowserSessionRegistry.class, () -> sessions)
        .withBean(IdeCommandTransport.class, () -> transport)
        .run(
            context -> {
              assertThat(context).hasSingleBean(IdeDataViewAuthorizer.class);
              IdeDataViewAuthorizer auth = context.getBean(IdeDataViewAuthorizer.class);
              assertThat(mockingDetails(auth).isMock()).isFalse();
              String cookie = "studio=irrelevant; custom_ide=" + token;
              var access = auth.authorize(env, view, cookie).orElseThrow();
              assertThat(access.controlIdentity()).isEqualTo(identity);
              assertThat(access.bindingEpoch()).isEqualTo(1);
              assertThat(access.authorizedWorkspaces()).containsExactly("/workspace");
              assertThat(auth.authorize(env, view, "other=" + token)).isEmpty();
              assertThat(auth.authorize(env, UUID.randomUUID(), cookie)).isEmpty();
              assertThat(
                      auth.authorize(
                          EnvironmentId.parse("66666666-6666-6666-6666-666666666666"),
                          view,
                          cookie))
                  .isEmpty();
              when(transport.connectionIdentity(env)).thenReturn(Optional.empty());
              assertThat(auth.authorize(env, view, cookie)).isPresent();
              when(transport.connectionIdentity(env))
                  .thenReturn(Optional.of(new DaemonConnectionIdentity(env, "replacement")));
              assertThat(auth.authorize(env, view, cookie)).isEmpty();
              when(transport.connectionIdentity(env)).thenReturn(Optional.of(identity));
              sessions.viewOpened(identity, view.toString(), 2, "/next");
              assertThat(auth.authorize(env, view, cookie)).isEmpty();
              String next = "custom_ide=" + sessions.open(identity, view.toString(), 2, "/next");
              assertThat(auth.authorize(env, view, next).orElseThrow().bindingEpoch()).isEqualTo(2);
              sessions.viewClosed(identity, view.toString(), 2);
              assertThat(auth.authorize(env, view, next)).isEmpty();
              sessions.viewOpened(identity, view.toString().toUpperCase(Locale.ROOT), 3, "/bad");
              String noncanonical =
                  "custom_ide="
                      + sessions.open(
                          identity, view.toString().toUpperCase(Locale.ROOT), 3, "/bad");
              assertThat(auth.authorize(env, view, noncanonical)).isEmpty();
            });
  }
}
