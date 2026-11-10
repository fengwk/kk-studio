package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 意图：SessionRegistry 只管理 cookie 生命周期，按完整绑定身份复用，并按精确 epoch 撤销。 */
class IdeBrowserSessionRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  private IdeBrowserSessionRegistry registry() {
    return registry(Clock.systemUTC(), Duration.ofHours(1));
  }

  private IdeBrowserSessionRegistry registry(Clock clock, Duration sessionTtl) {
    return new IdeBrowserSessionRegistry(new SecureRandom(), clock, sessionTtl);
  }

  @Test
  void sessionRequiresPositiveBindingEpoch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeBrowserSessionRegistry.Session(
                IDENTITY, UUID.randomUUID().toString(), 0L, "/workspace"),
        "cookie 会话不得接受非正 bindingEpoch");
  }

  @Test
  void lifecycleLookupAndExpiry() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    IdeBrowserSessionRegistry registry = registry(clock, Duration.ofHours(1));
    String viewId = UUID.randomUUID().toString();

    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");
    assertNotNull(token);
    Optional<IdeBrowserSessionRegistry.Session> session = registry.lookup(token, ENV, viewId);
    assertTrue(session.isPresent());
    assertEquals(ENV, session.get().environmentId());
    assertEquals("/workspace", session.get().workspacePath());

    now.set(now.get().plus(Duration.ofHours(2)));
    assertFalse(registry.lookup(token, ENV, viewId).isPresent());
  }

  @Test
  void sameBindingReusesTokenAndDifferentEpochGetsNewToken() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();

    String first = registry.open(IDENTITY, viewId, 1L, "/workspace");
    String same = registry.open(IDENTITY, viewId, 1L, "/workspace");
    String nextEpoch = registry.open(IDENTITY, viewId, 2L, "/workspace");

    assertEquals(first, same);
    assertNotEquals(first, nextEpoch);
    assertTrue(registry.lookup(first, ENV, viewId).isPresent());
    assertTrue(registry.lookup(nextEpoch, ENV, viewId).isPresent());
  }

  @Test
  void removeOnlyExactEpoch() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    String first = registry.open(IDENTITY, viewId, 1L, "/workspace");
    String second = registry.open(IDENTITY, viewId, 2L, "/workspace");

    registry.remove(IDENTITY, viewId, 1L);

    assertFalse(registry.lookup(first, ENV, viewId).isPresent());
    assertTrue(registry.lookup(second, ENV, viewId).isPresent());
  }

  @Test
  void lookupIsScopedByEnvironmentViewAndKnownToken() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");
    EnvironmentId otherEnv = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

    assertFalse(registry.lookup("unknown", ENV, viewId).isPresent());
    assertFalse(registry.lookup(token, otherEnv, viewId).isPresent());
    assertFalse(registry.lookup(token, ENV, UUID.randomUUID().toString()).isPresent());
  }
}
