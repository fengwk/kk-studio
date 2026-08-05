package fun.fengwk.kkstudio.harness.runtime.spring.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;

/** Config 校验：正整毫秒 Duration、maxDispatchTasks 与三类型 lease lookup。 */
class HarnessWorkDispatcherConfigTest {

  private static final Duration VALID = Duration.ofMillis(10);

  @Test
  void acceptsPositiveWholeMillisecondDurations() {
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(1),
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            Duration.ofMillis(500),
            3);
    assertEquals(Duration.ofSeconds(30), config.threadLeaseDuration());
    assertEquals(Duration.ofMillis(1), config.modelLeaseDuration());
    assertEquals(Duration.ofMinutes(1), config.toolLeaseDuration());
    assertEquals(Duration.ofSeconds(5), config.periodicPollInterval());
    assertEquals(Duration.ofMillis(500), config.executorRejectionDelay());
    assertEquals(3, config.maxDispatchTasks());
  }

  @Test
  void rejectsNullZeroNegativeAndSubMillisecondDurations() {
    Duration halfMillisecond = Duration.ofNanos(500_000);
    Duration oneAndHalfMillisecond = Duration.ofNanos(1_500_000);
    for (int i = 0; i < 5; i++) {
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

  @Test
  void looksUpTheLeaseDurationPerTargetType() {
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(45),
            Duration.ofSeconds(60),
            VALID,
            VALID,
            1);
    assertEquals(Duration.ofSeconds(30), config.leaseDuration(WorkTargetType.THREAD));
    assertEquals(Duration.ofSeconds(45), config.leaseDuration(WorkTargetType.MODEL));
    assertEquals(Duration.ofSeconds(60), config.leaseDuration(WorkTargetType.TOOL));
    assertThrows(NullPointerException.class, () -> config.leaseDuration(null));
  }

  private static HarnessWorkDispatcherConfig configWith(
      int index, Duration value, Duration fallback) {
    Duration[] durations = new Duration[] {fallback, fallback, fallback, fallback, fallback};
    durations[index] = value;
    return new HarnessWorkDispatcherConfig(
        durations[0], durations[1], durations[2], durations[3], durations[4], 1);
  }

  private static HarnessWorkDispatcherConfig configWithMax(int maxDispatchTasks) {
    return new HarnessWorkDispatcherConfig(VALID, VALID, VALID, VALID, VALID, maxDispatchTasks);
  }
}
