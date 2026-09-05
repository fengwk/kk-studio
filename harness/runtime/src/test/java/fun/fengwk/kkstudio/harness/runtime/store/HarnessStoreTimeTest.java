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

  @Test
  void notBeforeClampsCandidateToMaximumFloorAndRejectsNulls() {
    Instant t1 = Instant.parse("2026-08-05T00:00:01.000Z");
    Instant t2 = Instant.parse("2026-08-05T00:00:02.000Z");
    Instant t3 = Instant.parse("2026-08-05T00:00:03.000Z");

    // 当候选时间大于所有下界时，保持候选时间
    assertEquals(t3, HarnessStoreTime.notBefore(t3, t1, t2));

    // 当下界大于候选时间时，抬升到最大下界
    assertEquals(t3, HarnessStoreTime.notBefore(t1, t2, t3));

    // 空下界列表保持候选时间；任何 null 都说明调用方遗漏了 durable 事实。
    assertEquals(t2, HarnessStoreTime.notBefore(t2));
    assertThrows(
        NullPointerException.class, () -> HarnessStoreTime.notBefore(t2, (Instant[]) null));
    assertThrows(NullPointerException.class, () -> HarnessStoreTime.notBefore(t1, t2, null));

    // candidate 为 null 时必须抛 NPE 保持边界契约
    assertThrows(NullPointerException.class, () -> HarnessStoreTime.notBefore(null, t1));
  }
}
