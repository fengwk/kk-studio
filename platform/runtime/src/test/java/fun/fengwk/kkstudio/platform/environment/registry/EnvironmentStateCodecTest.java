package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;

import java.time.Instant;
import java.util.List;

/**
 * EnvironmentStateCodec 的事件往返与 fail-closed 测试。
 *
 * <p>两列只由本进程写入，因此解码必须严格：未知字段、错误类型、非法取值与损坏 JSON 一律抛错，绝不静默降级为「没有事实」。
 */
class EnvironmentStateCodecTest {

  private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");

  private final EnvironmentStateCodec codec = new EnvironmentStateCodec();

  /** 测试意图：单条事件编码结果可在事件数组中正确解码，并保留时间、级别、类型与去敏消息。 */
  @Test
  void eventsRoundTrip() {
    EnvironmentEvent event1 =
        new EnvironmentEvent(
            NOW, EnvironmentEventLevel.INFO, EnvironmentEventType.CONNECTING, "accepted");
    EnvironmentEvent event2 =
        new EnvironmentEvent(
            NOW.plusSeconds(1),
            EnvironmentEventLevel.WARN,
            EnvironmentEventType.DISCONNECTED,
            "daemon connection lost");
    String json = "[" + codec.encodeEvent(event1) + "," + codec.encodeEvent(event2) + "]";

    assertEquals(List.of(event1, event2), codec.decodeEvents(json));
  }

  /** 测试意图：事件未知字段、错误类型、非法取值与非数组内容一律 fail-closed。 */
  @Test
  void malformedProjectionsAreRejected() {
    String[] malformedEvents = {
      "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"INFO\",\"type\":\"READY\"}]",
      "[{\"time\":\"yesterday\",\"level\":\"INFO\",\"type\":\"READY\",\"message\":\"m\"}]",
      "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"TRACE\",\"type\":\"READY\",\"message\":\"m\"}]",
      "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"INFO\",\"type\":\"READY\",\"message\":\"m\",\"x\":1}]",
      "{}"
    };
    for (String json : malformedEvents) {
      assertThrows(
          RuntimeException.class, () -> codec.decodeEvents(json), "expected rejection of " + json);
    }
  }

  /** 测试意图：持久化投影中的非法级别与类型由解码边界以既有文案拒绝，级别检查先于类型。 */
  @Test
  void unknownLevelsAndTypesAreRejectedWithTheCanonicalMessages() {
    IllegalArgumentException level =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                codec.decodeEvents(
                    "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"TRACE\","
                        + "\"type\":\"READY\",\"message\":\"m\"}]"));
    assertEquals("unknown environment event level: TRACE", level.getMessage());

    IllegalArgumentException type =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                codec.decodeEvents(
                    "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"INFO\","
                        + "\"type\":\"SYNCING\",\"message\":\"m\"}]"));
    assertEquals("unknown environment event type: SYNCING", type.getMessage());

    // 两者同时非法时只报告级别：校验次序与旧实现一致。
    IllegalArgumentException levelFirst =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                codec.decodeEvents(
                    "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"TRACE\","
                        + "\"type\":\"SYNCING\",\"message\":\"m\"}]"));
    assertEquals("unknown environment event level: TRACE", levelFirst.getMessage());
  }

  /** 测试意图：事件类型自身的不变量在构造期即被拒绝。 */
  @Test
  void projectionInvariantsAreEnforced() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentEvent(NOW, EnvironmentEventLevel.INFO, EnvironmentEventType.READY, " "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentEvent(
                NOW,
                EnvironmentEventLevel.INFO,
                EnvironmentEventType.READY,
                "x".repeat(EnvironmentEvent.MAX_MESSAGE_CHARS + 1)));
  }
}
