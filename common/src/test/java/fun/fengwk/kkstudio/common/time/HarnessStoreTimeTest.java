package fun.fengwk.kkstudio.common.time;

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

  /** 命名字段校验保留非空要求与具体字段错误信息。 */
  @Test
  void namedPrecisionGuardRequiresNonNullAndMillisecondPrecision() {
    Instant canonical = Instant.parse("2026-08-05T00:00:00.123Z");

    assertEquals(canonical, HarnessStoreTime.requireMillisecondPrecision(canonical, "timestamp"));
    assertThrows(
        NullPointerException.class,
        () -> HarnessStoreTime.requireMillisecondPrecision(null, "timestamp"));
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HarnessStoreTime.requireMillisecondPrecision(canonical.plusNanos(1), "timestamp"));
    assertEquals("timestamp must use millisecond precision", exception.getMessage());
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
                Duration.ofNanos(500_000), "duration"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessStoreTime.requireWholeMillisecondDuration(
                Duration.ofNanos(1_500_000), "duration"));
    IllegalArgumentException overflow =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HarnessStoreTime.requireWholeMillisecondDuration(
                    Duration.ofSeconds(Long.MAX_VALUE), "duration"));
    assertEquals("duration must fit the millisecond range", overflow.getMessage());
  }

  @Test
  void rescheduleDurationsMustBeNonNegativeWholeMillisecondsWithinMillisecondRange() {
    Duration canonical = Duration.ofMillis(1500);

    // 零表示立即，合法。
    assertEquals(
        Duration.ZERO,
        HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(Duration.ZERO, "delay"));
    assertEquals(
        canonical, HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(canonical, "delay"));
    assertThrows(
        NullPointerException.class,
        () -> HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(null, "delay"));
    // 负值拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(
                Duration.ofMillis(-1), "delay"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(
                Duration.ofNanos(-1), "delay"));
    // 亚毫秒精度拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(
                Duration.ofNanos(1_500_000), "delay"));
    // 毫秒范围溢出拒绝（toMillis 溢出应表现为调用方可见的校验错误，而不是延迟到后续算术）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(
                Duration.ofSeconds(Long.MAX_VALUE), "delay"));
  }
}
