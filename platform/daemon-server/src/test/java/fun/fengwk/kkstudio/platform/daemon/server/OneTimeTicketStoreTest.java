package fun.fengwk.kkstudio.platform.daemon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

class OneTimeTicketStoreTest {

  @Test
  void issueReclaimsOnlyExpiredTickets() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    OneTimeTicketStore<String> store =
        new OneTimeTicketStore<>(new SecureRandom(), clock, Duration.ofMinutes(1));
    String first = store.issue("first");

    // 未过期时再次发行不误删已有 ticket。
    clock.advance(Duration.ofSeconds(30));
    String second = store.issue("second");
    assertEquals(2, store.pendingCount());

    // 恰好到达 TTL 时首次发行已过期，第二次发行仍未过期。
    clock.advance(Duration.ofSeconds(30));
    String third = store.issue("third");
    assertEquals(2, store.pendingCount());
    assertTrue(store.consume(first).isEmpty());
    assertEquals(Optional.of("second"), store.consume(second));
    assertEquals(Optional.of("third"), store.consume(third));
  }

  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

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
      return now;
    }
  }
}
