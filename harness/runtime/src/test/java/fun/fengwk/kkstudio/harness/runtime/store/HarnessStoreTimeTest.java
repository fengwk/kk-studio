package fun.fengwk.kkstudio.harness.runtime.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

class HarnessStoreTimeTest {

  @Test
  void millisecondClockCanonicalizesSourceSamples() {
    Instant source = Instant.parse("2026-08-05T00:00:00.123456789Z");
    Clock clock = HarnessStoreTime.millisecondClock(Clock.fixed(source, ZoneOffset.UTC));

    assertEquals(Instant.parse("2026-08-05T00:00:00.123Z"), clock.instant());
  }

  @Test
  void precisionGuardAcceptsCanonicalAndNullableValuesButRejectsSubMillisecondValues() {
    Instant canonical = Instant.parse("2026-08-05T00:00:00.123Z");

    assertEquals(canonical, HarnessStoreTime.requireMillisecondPrecision(canonical));
    assertNull(HarnessStoreTime.requireMillisecondPrecision(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessStoreTime.requireMillisecondPrecision(canonical.plusNanos(1)));
  }

  @Test
  void durableDurationsMustBePositiveWholeMilliseconds() {
    Duration canonical = Duration.ofMillis(1500);

    assertEquals(
        canonical, HarnessStoreTime.requireWholeMillisecondDuration(canonical, "duration"));
    assertThrows(
        NullPointerException.class,
        () -> HarnessStoreTime.requireWholeMillisecondDuration(null, "duration"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessStoreTime.requireWholeMillisecondDuration(Duration.ZERO, "duration"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessStoreTime.requireWholeMillisecondDuration(
                Duration.ofNanos(1_500_000), "duration"));
  }
}
