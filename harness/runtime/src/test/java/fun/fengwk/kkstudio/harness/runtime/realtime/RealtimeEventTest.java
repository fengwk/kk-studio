package fun.fengwk.kkstudio.harness.runtime.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;

import java.time.Instant;

/** Lossy realtime events retain enough durable identity for snapshot-first filtering. */
class RealtimeEventTest {

  @Test
  void modelDeltaExposesInvocationAttemptIdentity() {
    Instant createdAt = Instant.parse("2026-07-23T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            1L, 2L, 3, new ProviderStreamEvent.TextDelta("delta"), createdAt);

    assertEquals(1L, event.threadId());
    assertEquals(new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 2L), event.subject());
    assertEquals(3, event.attempt());
    assertEquals(RealtimeEventType.MODEL_DELTA, event.type());
    assertEquals(createdAt, event.createdAt());
  }

  @Test
  void modelDeltaRejectsInvalidDurableIdentity() {
    ProviderStreamEvent delta = new ProviderStreamEvent.TextDelta("delta");
    Instant now = Instant.parse("2026-07-23T00:00:00Z");

    assertThrows(
        IllegalArgumentException.class, () -> new RealtimeEvent.ModelDelta(0L, 2L, 1, delta, now));
    assertThrows(
        IllegalArgumentException.class, () -> new RealtimeEvent.ModelDelta(1L, 0L, 1, delta, now));
    assertThrows(
        IllegalArgumentException.class, () -> new RealtimeEvent.ModelDelta(1L, 2L, 0, delta, now));
    assertThrows(
        NullPointerException.class, () -> new RealtimeEvent.ModelDelta(1L, 2L, 1, null, now));
    assertThrows(
        NullPointerException.class, () -> new RealtimeEvent.ModelDelta(1L, 2L, 1, delta, null));
  }
}
