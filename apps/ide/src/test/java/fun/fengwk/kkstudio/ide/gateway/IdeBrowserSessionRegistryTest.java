package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class IdeBrowserSessionRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  @Test
  void lifecycleAndLookup() {
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

    IdeBrowserSessionRegistry registry =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofHours(1));

    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");

    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");
    assertNotNull(token);
    assertEquals(1, registry.size());
    assertEquals(1, registry.viewCount(IDENTITY));
    assertEquals(1, registry.sessionCount(ENV, viewId));

    Optional<IdeBrowserSessionRegistry.Session> session = registry.lookup(token, ENV, viewId);
    assertTrue(session.isPresent());
    assertEquals("/workspace", session.get().workspacePath());
    assertTrue(session.get().authorizesWorkspace("/workspace"));
    assertFalse(session.get().authorizesWorkspace("/other"));

    // Expiration
    now.set(now.get().plus(Duration.ofHours(2)));
    assertFalse(registry.lookup(token, ENV, viewId).isPresent());
    assertEquals(0, registry.size());
  }

  @Test
  void viewClosedAndCloseGeneration() {
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");
    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");

    registry.viewClosed(IDENTITY, viewId, 1L);
    assertFalse(registry.lookup(token, ENV, viewId).isPresent());

    registry.viewOpened(IDENTITY, viewId, 2L, "/workspace");
    String token2 = registry.open(IDENTITY, viewId, 2L, "/workspace");
    assertTrue(registry.lookup(token2, ENV, viewId).isPresent());

    registry.closeGeneration(IDENTITY);
    assertFalse(registry.lookup(token2, ENV, viewId).isPresent());
  }

  @Test
  void capacityConstraints() {
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    for (int i = 0; i < IdeBrowserSessionRegistry.MAX_VIEWS_PER_GENERATION; i++) {
      registry.viewOpened(IDENTITY, UUID.randomUUID().toString(), 1L, "/workspace");
    }
    assertThrows(
        IllegalStateException.class,
        () -> registry.viewOpened(IDENTITY, UUID.randomUUID().toString(), 1L, "/workspace"));
    assertThrows(IllegalStateException.class, () -> registry.ensureViewCapacity(ENV));
  }
}
