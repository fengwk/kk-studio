package fun.fengwk.kkstudio.web.environment.ide;

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
import java.util.concurrent.atomic.AtomicReference;

/** 意图：浏览器 cookie 绑定 view+epoch，过期、跨 view、epoch 换代与 Daemon 实例关闭都立即失效；迟到关闭旧 epoch 不得删除新 epoch。 */
class IdeBrowserSessionRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-instance");
  private static final String VIEW_1 = "11111111-2222-3333-4444-555555555555";
  private static final String VIEW_2 = "66666666-7777-8888-9999-aaaaaaaaaaaa";
  private static final Instant START = Instant.parse("2026-10-28T00:00:00Z");

  @Test
  void cookieExpiresAndIsNotAcceptedForAnotherView() {
    MutableClock clock = new MutableClock(START);
    IdeBrowserSessionRegistry registry =
        new IdeBrowserSessionRegistry(new SecureRandom(), clock, Duration.ofMinutes(5));
    registry.viewOpened(IDENTITY, VIEW_1, 1L, "/ws/1");
    String cookie = registry.open(IDENTITY, VIEW_1, 1L, "/ws/1");

    assertTrue(registry.lookup(cookie, ENV, VIEW_1).isPresent());
    assertTrue(registry.lookup(cookie, ENV, VIEW_2).isEmpty());

    clock.advance(Duration.ofMinutes(6));
    assertTrue(registry.lookup(cookie, ENV, VIEW_1).isEmpty());
  }

  @Test
  void newEpochInvalidatesOldCookieAndLateOldCloseCannotRemoveNewEpoch() {
    IdeBrowserSessionRegistry registry =
        new IdeBrowserSessionRegistry(
            new SecureRandom(), Clock.fixed(START, ZoneOffset.UTC), Duration.ofMinutes(30));
    registry.viewOpened(IDENTITY, VIEW_1, 1L, "/ws");
    String oldCookie = registry.open(IDENTITY, VIEW_1, 1L, "/ws");

    registry.viewOpened(IDENTITY, VIEW_1, 2L, "/ws");
    assertTrue(registry.lookup(oldCookie, ENV, VIEW_1).isEmpty());
    String newCookie = registry.open(IDENTITY, VIEW_1, 2L, "/ws");

    registry.viewClosed(IDENTITY, VIEW_1, 1L);
    assertTrue(registry.lookup(newCookie, ENV, VIEW_1).isPresent());

    registry.viewClosed(IDENTITY, VIEW_1, 2L);
    assertTrue(registry.lookup(newCookie, ENV, VIEW_1).isEmpty());
    assertThrows(IllegalStateException.class, () -> registry.open(IDENTITY, VIEW_1, 2L, "/ws"));
  }

  @Test
  void generationCloseRemovesAllViewCookies() {
    IdeBrowserSessionRegistry registry =
        new IdeBrowserSessionRegistry(
            new SecureRandom(), Clock.fixed(START, ZoneOffset.UTC), Duration.ofMinutes(30));
    registry.viewOpened(IDENTITY, VIEW_1, 1L, "/ws/1");
    registry.viewOpened(IDENTITY, VIEW_2, 2L, "/ws/2");
    String first = registry.open(IDENTITY, VIEW_1, 1L, "/ws/1");
    String second = registry.open(IDENTITY, VIEW_2, 2L, "/ws/2");

    registry.closeGeneration(IDENTITY);

    assertTrue(registry.lookup(first, ENV, VIEW_1).isEmpty());
    assertTrue(registry.lookup(second, ENV, VIEW_2).isEmpty());
  }

  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    private MutableClock(Instant now) {
      this.now = new AtomicReference<>(now);
    }

    private void advance(Duration duration) {
      now.updateAndGet(value -> value.plus(duration));
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
      return now.get();
    }
  }
}
