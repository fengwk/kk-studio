package fun.fengwk.kkstudio.platform.harness.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** Config 校验：正整毫秒 Duration 与 maxDispatchTasks。 */
class HarnessWorkDispatcherConfigTest {

  private static final Duration VALID = Duration.ofMillis(10);

  @Test
  void acceptsPositiveWholeMillisecondDurations() {
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(500), 3);
    assertEquals(Duration.ofSeconds(30), config.leaseDuration());
    assertEquals(Duration.ofSeconds(5), config.periodicPollInterval());
    assertEquals(Duration.ofMillis(500), config.executorRejectionDelay());
    assertEquals(3, config.maxDispatchTasks());
  }

  @Test
  void rejectsNullZeroNegativeAndSubMillisecondDurations() {
    Duration halfMillisecond = Duration.ofNanos(500_000);
    Duration oneAndHalfMillisecond = Duration.ofNanos(1_500_000);
    for (int i = 0; i < 3; i++) {
      int index = i;
      Duration nullValue = null;
      Duration zero = Duration.ZERO;
      Duration negative = Duration.ofMillis(-1);
      Duration subMillisecond = index % 2 == 0 ? halfMillisecond : oneAndHalfMillisecond;
      assertThrows(
          NullPointerException.class,
          () -> configWith(index, nullValue, VALID),
          "null duration at index " + index);
      assertThrows(
          IllegalArgumentException.class,
          () -> configWith(index, zero, VALID),
          "zero duration at index " + index);
      assertThrows(
          IllegalArgumentException.class,
          () -> configWith(index, negative, VALID),
          "negative duration at index " + index);
      assertThrows(
          IllegalArgumentException.class,
          () -> configWith(index, subMillisecond, VALID),
          "sub-millisecond duration at index " + index);
    }
  }

  @Test
  void rejectsNonPositiveMaxDispatchTasks() {
    assertThrows(IllegalArgumentException.class, () -> configWithMax(0));
    assertThrows(IllegalArgumentException.class, () -> configWithMax(-1));
  }

  private static HarnessWorkDispatcherConfig configWith(
      int index, Duration value, Duration fallback) {
    Duration[] durations = new Duration[] {fallback, fallback, fallback};
    durations[index] = value;
    return new HarnessWorkDispatcherConfig(durations[0], durations[1], durations[2], 1);
  }

  private static HarnessWorkDispatcherConfig configWithMax(int maxDispatchTasks) {
    return new HarnessWorkDispatcherConfig(VALID, VALID, VALID, maxDispatchTasks);
  }
}
