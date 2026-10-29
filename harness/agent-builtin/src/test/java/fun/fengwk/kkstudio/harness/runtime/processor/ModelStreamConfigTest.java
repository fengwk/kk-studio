package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 推送窗口与 checkpoint 节奏的配置不变量：默认值、毫秒精度与增量载荷 UTF-8 计量。 */
class ModelStreamConfigTest {

  @Test
  void defaultsMatchPlanB() {
    assertEquals(Duration.ofMillis(50), ModelStreamConfig.DEFAULT.pushWindow());
    assertEquals(256, ModelStreamConfig.DEFAULT.maxBatchEvents());
    assertEquals(64 * 1024, ModelStreamConfig.DEFAULT.maxBatchBytes());
    assertEquals(Duration.ofMillis(1500), ModelStreamConfig.DEFAULT.checkpointInterval());
  }

  @Test
  void rejectsNonPositiveOrSubMillisecondWindowsAndIntervals() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelStreamConfig(Duration.ZERO, 1, 1, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelStreamConfig(Duration.ofNanos(999_999), 1, 1, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelStreamConfig(Duration.ofMillis(10), 1, 1, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelStreamConfig(Duration.ofMillis(10), 1, 1, Duration.ofNanos(1_500_001)));
    assertThrows(
        NullPointerException.class, () -> new ModelStreamConfig(null, 1, 1, Duration.ofSeconds(1)));
    assertThrows(
        NullPointerException.class, () -> new ModelStreamConfig(Duration.ofMillis(10), 1, 1, null));
  }

  @Test
  void rejectsNonPositiveBatchLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelStreamConfig(Duration.ofMillis(10), 0, 1, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelStreamConfig(Duration.ofMillis(10), 1, 0, Duration.ofSeconds(1)));
  }

  @Test
  void countsUtf8PayloadBytesWithoutBuildingCumulativeText() {
    String chinese = "你好";
    assertEquals(
        chinese.getBytes(StandardCharsets.UTF_8).length,
        ModelStreamConfig.eventPayloadBytes(new ProviderStreamEvent.TextDelta(chinese)));
    assertEquals(
        3, ModelStreamConfig.eventPayloadBytes(new ProviderStreamEvent.ThinkingDelta("abc")));
    assertEquals(
        8,
        ModelStreamConfig.eventPayloadBytes(
            new ProviderStreamEvent.ToolCallDelta(0, "id", "name", "{}")));
  }

  @Test
  void toleratesNullableToolFragmentFieldsAndRejectsNullEvent() {
    assertEquals(
        2,
        ModelStreamConfig.eventPayloadBytes(
            new ProviderStreamEvent.ToolCallDelta(0, "id", null, null)));
    assertEquals(
        1,
        ModelStreamConfig.eventPayloadBytes(
            new ProviderStreamEvent.ToolCallDelta(0, null, null, "{")),
        "nullable id/name must not be counted as bytes");
    assertThrows(NullPointerException.class, () -> ModelStreamConfig.eventPayloadBytes(null));
  }
}
