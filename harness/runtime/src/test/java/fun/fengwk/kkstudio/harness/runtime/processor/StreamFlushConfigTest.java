package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** StreamFlushConfig 单元测试：校验参数边界、生产初值与 UTF-8 载荷计算。 */
class StreamFlushConfigTest {

  @Test
  void defaultValuesMatchSpecification() {
    StreamFlushConfig config = StreamFlushConfig.DEFAULT;
    assertEquals(Duration.ofMillis(200), config.maxDelay());
    assertEquals(256, config.maxEvents());
    assertEquals(65536, config.maxPayloadBytes());
  }

  @Test
  void rejectsInvalidParameters() {
    assertThrows(NullPointerException.class, () -> new StreamFlushConfig(null, 256, 1024));
    assertThrows(
        IllegalArgumentException.class, () -> new StreamFlushConfig(Duration.ZERO, 256, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamFlushConfig(Duration.ofMillis(-1), 256, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamFlushConfig(Duration.ofNanos(500), 256, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamFlushConfig(Duration.ofMillis(100), 0, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamFlushConfig(Duration.ofMillis(100), -1, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamFlushConfig(Duration.ofMillis(100), 256, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamFlushConfig(Duration.ofMillis(100), 256, -1));
  }

  @Test
  void computesEventPayloadBytesAccurately() {
    String chineseText = "你好，世界";
    int chineseBytes = chineseText.getBytes(StandardCharsets.UTF_8).length;

    ProviderStreamEvent textDelta = new ProviderStreamEvent.TextDelta(chineseText);
    assertEquals((long) chineseBytes, StreamFlushConfig.eventPayloadBytes(textDelta));

    ProviderStreamEvent thinkingDelta = new ProviderStreamEvent.ThinkingDelta("reasoning...");
    assertEquals(12L, StreamFlushConfig.eventPayloadBytes(thinkingDelta));

    ProviderStreamEvent toolDelta =
        new ProviderStreamEvent.ToolCallDelta(0, "call_123", "bash", "{\"cmd\":\"ls\"}");
    long expectedToolBytes =
        (long) "call_123".getBytes(StandardCharsets.UTF_8).length
            + "bash".getBytes(StandardCharsets.UTF_8).length
            + "{\"cmd\":\"ls\"}".getBytes(StandardCharsets.UTF_8).length;
    assertEquals(expectedToolBytes, StreamFlushConfig.eventPayloadBytes(toolDelta));

    ProviderStreamEvent toolDeltaPartial =
        new ProviderStreamEvent.ToolCallDelta(0, null, null, "}");
    assertEquals(1L, StreamFlushConfig.eventPayloadBytes(toolDeltaPartial));
  }
}
