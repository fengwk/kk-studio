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

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 意图：SessionRegistry 只管理 cookie 生命周期，按完整绑定身份复用并独立过期，过期与旧 epoch 条目不会无限滞留。 */
class IdeBrowserSessionRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");
  private static final UUID USER_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

  private IdeBrowserSessionRegistry registry() {
    return registry(new MutableClock(Instant.parse("2026-01-01T00:00:00Z")), Duration.ofHours(1));
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
                IDENTITY, UUID.randomUUID().toString(), 0L, "/workspace", USER_ID),
        "cookie 会话不得接受非正 bindingEpoch");
  }

  @Test
  void lifecycleLookupAndExpiry() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry registry = registry(clock, Duration.ofHours(1));
    String viewId = UUID.randomUUID().toString();

    String token = registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);
    assertNotNull(token);
    Optional<IdeBrowserSessionRegistry.Session> session = registry.lookup(token, ENV, viewId);
    assertTrue(session.isPresent());
    assertEquals(ENV, session.get().identity().environmentId());
    assertEquals("/workspace", session.get().workspacePath());

    clock.advance(Duration.ofHours(2));
    assertFalse(registry.lookup(token, ENV, viewId).isPresent());
  }

  @Test
  void sameBindingReusesTokenAndDifferentEpochGetsNewToken() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();

    String first = registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);
    String same = registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);
    String nextEpoch = registry.open(IDENTITY, viewId, 2L, "/workspace", USER_ID);

    assertEquals(first, same);
    assertNotEquals(first, nextEpoch);
    assertFalse(
        registry.lookup(first, ENV, viewId).isPresent(),
        "同一 view 换代后旧 epoch 的 cookie 不再登记，避免历史 epoch 永久滞留");
    assertTrue(registry.lookup(nextEpoch, ENV, viewId).isPresent());
  }

  @Test
  void lookupIsScopedByEnvironmentViewAndKnownToken() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    String token = registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);
    EnvironmentId otherEnv = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

    assertFalse(registry.lookup("unknown", ENV, viewId).isPresent());
    assertFalse(registry.lookup(token, otherEnv, viewId).isPresent());
    assertFalse(registry.lookup(token, ENV, UUID.randomUUID().toString()).isPresent());
  }

  @Test
  void expiredSameBindingGetsFreshToken() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry registry = registry(clock, Duration.ofHours(1));
    String viewId = UUID.randomUUID().toString();
    String first = registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);

    clock.advance(Duration.ofHours(2));
    String renewed = registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);

    assertNotEquals(first, renewed, "过期会话不复用旧 cookie 值");
    assertFalse(registry.lookup(first, ENV, viewId).isPresent());
    assertTrue(registry.lookup(renewed, ENV, viewId).isPresent());
  }

  @Test
  void staleEpochEntriesOfSameViewAreReclaimedAndCountStaysBounded() throws Exception {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();
    String previous = null;

    for (long epoch = 1L; epoch <= 20L; epoch++) {
      String token = registry.open(IDENTITY, viewId, epoch, "/workspace", USER_ID);
      if (previous != null) {
        assertFalse(
            registry.lookup(previous, ENV, viewId).isPresent(),
            "epoch " + epoch + " 打开后 epoch " + (epoch - 1) + " 的条目应被回收");
      }
      assertEquals(1, sessionCount(registry), "同一 view 只保留当前 epoch 一条会话");
      assertEquals(tokenBySessionKeyCount(registry), sessionCount(registry), "两个索引必须同步清理");
      assertTrue(registry.lookup(token, ENV, viewId).isPresent(), "当前 epoch 仍可用");
      previous = token;
    }
  }

  @Test
  void expiredEntriesOfAbandonedViewsAreReclaimedOnNextOpen() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    IdeBrowserSessionRegistry registry = registry(clock, Duration.ofHours(1));
    String abandonedView = UUID.randomUUID().toString();
    String abandoned = registry.open(IDENTITY, abandonedView, 1L, "/workspace", USER_ID);
    assertTrue(registry.lookup(abandoned, ENV, abandonedView).isPresent());

    clock.advance(Duration.ofHours(2));
    String currentView = UUID.randomUUID().toString();
    String current = registry.open(IDENTITY, currentView, 1L, "/workspace", USER_ID);

    assertEquals(1, sessionCount(registry), "过期条目不参与任何后续授权，应在下一次 open 时回收");
    assertEquals(tokenBySessionKeyCount(registry), sessionCount(registry), "两个索引必须同步清理");
    assertTrue(registry.lookup(current, ENV, currentView).isPresent());
  }

  @Test
  void staleEpochOpenDoesNotRevokeNewerSession() {
    IdeBrowserSessionRegistry registry = registry();
    String viewId = UUID.randomUUID().toString();

    String current = registry.open(IDENTITY, viewId, 2L, "/workspace", USER_ID);
    registry.open(IDENTITY, viewId, 1L, "/workspace", USER_ID);

    assertTrue(
        registry.lookup(current, ENV, viewId).isPresent(), "迟到的旧 epoch 不能再撤销新 epoch 已签发的 cookie");
  }

  private static int sessionCount(IdeBrowserSessionRegistry registry) throws Exception {
    return mapSize(registry, "sessions");
  }

  private static int tokenBySessionKeyCount(IdeBrowserSessionRegistry registry) throws Exception {
    return mapSize(registry, "tokenBySessionKey");
  }

  /** 条目数上界是本类唯一的内存契约，公开 API 只能观察单个 token，因此这里直接读内部索引。 */
  private static int mapSize(IdeBrowserSessionRegistry registry, String fieldName)
      throws Exception {
    Field field = IdeBrowserSessionRegistry.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return ((Map<?, ?>) field.get(registry)).size();
  }

  /** 可推进的测试时钟。 */
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
