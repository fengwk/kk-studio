package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCurrentGeneration;
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

class IdeBrowserSessionRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  private final Generation generation = new Generation(IDENTITY);

  private IdeBrowserSessionRegistry registry() {
    return registry(Duration.ofHours(1));
  }

  private IdeBrowserSessionRegistry registry(Duration sessionTtl) {
    return new IdeBrowserSessionRegistry(
        new SecureRandom(), Clock.systemUTC(), sessionTtl, generation);
  }

  private IdeBrowserSessionRegistry registry(Clock clock, Duration sessionTtl) {
    return new IdeBrowserSessionRegistry(new SecureRandom(), clock, sessionTtl, generation);
  }

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

    IdeBrowserSessionRegistry registry = registry(clock, Duration.ofHours(1));

    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");

    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");
    assertNotNull(token);

    Optional<IdeBrowserSessionRegistry.Session> session = registry.lookup(token, ENV, viewId);
    assertTrue(session.isPresent());
    assertEquals(ENV, session.get().environmentId());
    assertEquals("/workspace", session.get().workspacePath());

    // Expiration
    now.set(now.get().plus(Duration.ofHours(2)));
    assertFalse(registry.lookup(token, ENV, viewId).isPresent());
  }

  @Test
  void viewClosed() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");
    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");

    registry.viewClosed(IDENTITY, viewId, 1L);
    assertFalse(registry.lookup(token, ENV, viewId).isPresent());
  }

  @Test
  void closedGenerationIsReclaimedAfterLastView() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();

    // 关闭后旧 cookie 失效，重新打开的新 epoch 仍拒绝旧 epoch。
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");
    String oldToken = registry.open(IDENTITY, viewId, 1L, "/workspace");
    registry.viewClosed(IDENTITY, viewId, 1L);
    assertFalse(registry.lookup(oldToken, ENV, viewId).isPresent());
    registry.viewOpened(IDENTITY, viewId, 2L, "/workspace");
    assertThrows(
        IllegalStateException.class, () -> registry.open(IDENTITY, viewId, 1L, "/workspace"));
    registry.viewClosed(IDENTITY, viewId, 2L);

    // 每个 identity 只经历一次 open-close；空代际被回收后仍可持续打开超过上限的次数。
    for (int i = 0; i <= IdeBrowserSessionRegistry.MAX_GENERATIONS; i++) {
      DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "daemon-" + i);
      String cycleViewId = UUID.randomUUID().toString();
      generation.set(identity);
      registry.viewOpened(identity, cycleViewId, 1L, "/workspace");
      String token = registry.open(identity, cycleViewId, 1L, "/workspace");
      assertTrue(registry.lookup(token, ENV, cycleViewId).isPresent());

      registry.viewClosed(identity, cycleViewId, 1L);
      assertFalse(registry.lookup(token, ENV, cycleViewId).isPresent());
    }
  }

  @Test
  void capacityConstraints() {
    IdeBrowserSessionRegistry registry = registry();
    for (int i = 0; i < IdeBrowserSessionRegistry.MAX_VIEWS_PER_GENERATION; i++) {
      registry.viewOpened(IDENTITY, UUID.randomUUID().toString(), 1L, "/workspace");
    }
    assertThrows(
        IllegalStateException.class,
        () -> registry.viewOpened(IDENTITY, UUID.randomUUID().toString(), 1L, "/workspace"));
    assertThrows(IllegalStateException.class, () -> registry.ensureViewCapacity(IDENTITY));
  }

  /** 意图：新进程复用相同 Environment/view/epoch/workspace 时得到新 token，旧 token 与迟到旧 close 都不影响新会话。 */
  @Test
  void replacedDaemonInstanceGetsItsOwnGenerationAndToken() {
    IdeBrowserSessionRegistry registry = registry();
    DaemonConnectionIdentity first = new DaemonConnectionIdentity(ENV, "daemon-1");
    DaemonConnectionIdentity replacement = new DaemonConnectionIdentity(ENV, "daemon-2");
    String viewId = UUID.randomUUID().toString();

    registry.viewOpened(first, viewId, 1L, "/workspace");
    String firstToken = registry.open(first, viewId, 1L, "/workspace");
    assertTrue(registry.lookup(firstToken, ENV, viewId).isPresent());

    // 同一进程重连：重新确认容量与重新登记同一视图都不撤销既有会话。
    registry.ensureViewCapacity(first);
    registry.viewOpened(first, viewId, 1L, "/workspace");
    assertEquals(firstToken, registry.open(first, viewId, 1L, "/workspace"));

    // 新进程在授权入口接管：旧代际登记与 token 被清除，不复用旧 token。
    generation.set(replacement);
    registry.ensureViewCapacity(replacement);
    assertFalse(registry.lookup(firstToken, ENV, viewId).isPresent());
    assertThrows(IllegalStateException.class, () -> registry.open(first, viewId, 1L, "/workspace"));
    registry.viewOpened(replacement, viewId, 1L, "/workspace");
    String replacementToken = registry.open(replacement, viewId, 1L, "/workspace");
    assertNotEquals(firstToken, replacementToken);
    assertTrue(registry.lookup(replacementToken, ENV, viewId).isPresent());

    // 旧进程迟到的登记即使已通过调用方的锁外检查，也在登记锁内被拒绝，不清理新代际。
    assertThrows(
        IllegalStateException.class, () -> registry.viewOpened(first, viewId, 1L, "/workspace"));
    assertTrue(registry.lookup(replacementToken, ENV, viewId).isPresent());

    // 旧进程迟到的 close 只能命中旧代际，不影响新会话。
    registry.viewClosed(first, viewId, 1L);
    assertTrue(registry.lookup(replacementToken, ENV, viewId).isPresent());
  }

  /** 意图：旧代际已满不阻断新实例授权，容量按当前实例自身计算。 */
  @Test
  void fullPreviousGenerationDoesNotBlockReplacementInstance() {
    IdeBrowserSessionRegistry registry = registry();
    for (int i = 0; i < IdeBrowserSessionRegistry.MAX_VIEWS_PER_GENERATION; i++) {
      registry.viewOpened(IDENTITY, UUID.randomUUID().toString(), 1L, "/workspace");
    }
    assertThrows(IllegalStateException.class, () -> registry.ensureViewCapacity(IDENTITY));

    DaemonConnectionIdentity replacement = new DaemonConnectionIdentity(ENV, "daemon-2");
    generation.set(replacement);
    registry.ensureViewCapacity(replacement);
    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(replacement, viewId, 1L, "/workspace");

    assertTrue(
        registry
            .lookup(registry.open(replacement, viewId, 1L, "/workspace"), ENV, viewId)
            .isPresent());
  }

  @Test
  void lookupAndOpenEdgeCases() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");

    String token = registry.open(IDENTITY, viewId, 1L, "/workspace");

    // Re-opening with same parameters returns existing token
    String refreshedToken = registry.open(IDENTITY, viewId, 1L, "/workspace");
    assertEquals(token, refreshedToken);

    // Open with mismatched epoch or workspace throws IllegalStateException
    assertThrows(
        IllegalStateException.class, () -> registry.open(IDENTITY, viewId, 2L, "/workspace"));
    assertThrows(IllegalStateException.class, () -> registry.open(IDENTITY, viewId, 1L, "/other"));

    // Lookup with blank or null
    assertFalse(registry.lookup(null, ENV, viewId).isPresent());
    assertFalse(registry.lookup("  ", ENV, viewId).isPresent());
    assertFalse(registry.lookup("non-existent", ENV, viewId).isPresent());

    // Lookup with mismatched env or viewId
    EnvironmentId otherEnv = EnvironmentId.parse("44444444-4444-4444-4444-444444444444");
    assertFalse(registry.lookup(token, otherEnv, viewId).isPresent());
    assertFalse(registry.lookup(token, ENV, "other-view").isPresent());

    // View closed with wrong epoch does not remove view
    registry.viewClosed(IDENTITY, viewId, 999L);
    assertTrue(registry.lookup(token, ENV, viewId).isPresent());

    // View closed with non-existent identity does not throw
    registry.viewClosed(new DaemonConnectionIdentity(otherEnv, "daemon-x"), viewId, 1L);
    assertTrue(registry.lookup(token, ENV, viewId).isPresent());

    // Session record validation
    assertThrows(
        NullPointerException.class,
        () -> new IdeBrowserSessionRegistry.Session(null, viewId, 1L, "/workspace"));
    assertThrows(
        NullPointerException.class,
        () -> new IdeBrowserSessionRegistry.Session(IDENTITY, null, 1L, "/workspace"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdeBrowserSessionRegistry.Session(IDENTITY, viewId, 0L, "/workspace"));
    assertThrows(
        NullPointerException.class,
        () -> new IdeBrowserSessionRegistry.Session(IDENTITY, viewId, 1L, null));

    // viewOpened validation
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.viewOpened(IDENTITY, viewId, 0L, "/workspace"));
  }

  @Test
  void viewReopenedWithDifferentWorkspaceOrEpochInvalidatesSessions() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace1");
    String token1 = registry.open(IDENTITY, viewId, 1L, "/workspace1");
    assertTrue(registry.lookup(token1, ENV, viewId).isPresent());

    // Reopen same epoch with different workspace -> invalidates all sessions
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace2");
    assertFalse(registry.lookup(token1, ENV, viewId).isPresent());

    // Open new session on workspace2
    String token2 = registry.open(IDENTITY, viewId, 1L, "/workspace2");
    assertTrue(registry.lookup(token2, ENV, viewId).isPresent());

    // Advance epoch -> invalidates older epoch sessions
    registry.viewOpened(IDENTITY, viewId, 2L, "/workspace2");
    assertFalse(registry.lookup(token2, ENV, viewId).isPresent());
  }

  @Test
  void cleanupExpiredSessionsAndEnsureCapacityMultiEnv() {
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

    IdeBrowserSessionRegistry registry = registry(clock, Duration.ofMinutes(30));

    String viewId = UUID.randomUUID().toString();
    registry.viewOpened(IDENTITY, viewId, 1L, "/workspace");
    String token1 = registry.open(IDENTITY, viewId, 1L, "/workspace");
    assertTrue(registry.lookup(token1, ENV, viewId).isPresent());

    // Multi-environment capacity check (tests loop continue branch)
    EnvironmentId otherEnv = EnvironmentId.parse("33333333-3333-3333-3333-333333333333");
    DaemonConnectionIdentity otherDaemon = new DaemonConnectionIdentity(otherEnv, "daemon-other");
    generation.set(otherDaemon);
    registry.ensureViewCapacity(otherDaemon);
    generation.set(IDENTITY);

    // Advance past TTL and reopen -> invalidates expired existing token and creates new token
    now.set(now.get().plus(Duration.ofHours(1)));
    String token2 = registry.open(IDENTITY, viewId, 1L, "/workspace");
    assertNotNull(token2);
    assertTrue(registry.lookup(token2, ENV, viewId).isPresent());
  }

  /** 可控的当前 READY 实例：测试切换身份以模拟 Daemon 进程替换。 */
  private static final class Generation implements IdeCurrentGeneration {

    private volatile DaemonConnectionIdentity current;

    private Generation(DaemonConnectionIdentity current) {
      this.current = current;
    }

    private void set(DaemonConnectionIdentity identity) {
      this.current = identity;
    }

    @Override
    public Optional<DaemonConnectionIdentity> current(EnvironmentId environmentId) {
      DaemonConnectionIdentity identity = current;
      return identity.environmentId().equals(environmentId)
          ? Optional.of(identity)
          : Optional.empty();
    }
  }
}
