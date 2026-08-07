package fun.fengwk.kkstudio.harness.runtime.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** 有损 realtime event 必须保留足够的 durable identity，以支持 snapshot-first 过滤。 */
class RealtimeEventTest {

  @Test
  void modelDeltaExposesInvocationAttemptIdentity() {
    Instant createdAt = Instant.parse("2026-07-23T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            1L, 2L, 3, 4L, new ProviderStreamEvent.TextDelta("delta"), createdAt);

    assertEquals(1L, event.threadId());
    assertEquals(
        new RealtimeEvent.Subject(RealtimeEvent.SubjectKind.MODEL_INVOCATION, 2L), event.subject());
    assertEquals(3, event.attempt());
    assertEquals(4L, event.sequence());
    assertEquals(RealtimeEventType.MODEL_DELTA, event.type());
    assertEquals(createdAt, event.createdAt());
  }

  @Test
  void modelDeltaRejectsInvalidDurableIdentity() {
    ProviderStreamEvent delta = new ProviderStreamEvent.TextDelta("delta");
    Instant now = Instant.parse("2026-07-23T00:00:00Z");

    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ModelDelta(0L, 2L, 1, 1L, delta, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ModelDelta(1L, 0L, 1, 1L, delta, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ModelDelta(1L, 2L, 0, 1L, delta, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ModelDelta(1L, 2L, 1, 0L, delta, now));
    assertThrows(
        NullPointerException.class, () -> new RealtimeEvent.ModelDelta(1L, 2L, 1, 1L, null, now));
    assertThrows(
        NullPointerException.class, () -> new RealtimeEvent.ModelDelta(1L, 2L, 1, 1L, delta, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ModelDelta(1L, 2L, 1, 1L, delta, now.plusNanos(1)));
  }

  @Test
  void toolPartialExposesIdentityAndRejectsInvalidValues() {
    Instant now = Instant.parse("2026-07-23T00:00:00Z");
    ToolResult partial =
        new ToolResult("call-1", List.of(new TextToolContent("partial")), false, "{}", false);
    RealtimeEvent.ToolPartial event = new RealtimeEvent.ToolPartial(1L, 2L, 3, partial, now);

    assertEquals(
        new RealtimeEvent.Subject(RealtimeEvent.SubjectKind.TOOL_INVOCATION, 2L), event.subject());
    assertEquals(RealtimeEventType.TOOL_PARTIAL, event.type());
    assertEquals(now, event.createdAt());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ToolPartial(0L, 2L, 1, partial, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ToolPartial(1L, 0L, 1, partial, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ToolPartial(1L, 2L, 0, partial, now));
    assertThrows(
        NullPointerException.class, () -> new RealtimeEvent.ToolPartial(1L, 2L, 1, null, now));
    assertThrows(
        NullPointerException.class, () -> new RealtimeEvent.ToolPartial(1L, 2L, 1, partial, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RealtimeEvent.ToolPartial(1L, 2L, 1, partial, now.plusNanos(1)));
  }
}
