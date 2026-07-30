package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;

import java.time.Instant;

/** Verifies nullable head and durable execution state projection at the Core DTO boundary. */
class HarnessThreadDtoConverterTest {

  private static final Instant CREATED_AT = Instant.parse("2026-07-24T00:00:00Z");
  private static final Instant UPDATED_AT = Instant.parse("2026-07-24T00:00:01Z");

  /**
   * The three non-processing states are derived only from runnable and nullable head durable facts.
   */
  @Test
  void projectsUnboundIdleAndRunnableStates() {
    HarnessThreadDtoConverter converter = new HarnessThreadDtoConverter();

    HarnessThreadDTO unbound =
        converter.convert(
            new HarnessThread(1L, null, 2L, false, 3L, 0L, null, CREATED_AT, UPDATED_AT));
    HarnessThreadDTO idle =
        converter.convert(
            new HarnessThread(2L, 10L, 4L, false, 5L, 0L, null, CREATED_AT, UPDATED_AT));
    HarnessThreadDTO runnable =
        converter.convert(
            new HarnessThread(3L, 11L, 6L, true, 7L, 0L, null, CREATED_AT, UPDATED_AT));

    assertEquals("UNBOUND", unbound.getStatus());
    assertNull(unbound.getHeadEntryId());
    assertEquals(3L, unbound.getExecutionEpoch());
    assertFalse(unbound.getProcessing());
    assertEquals("IDLE", idle.getStatus());
    assertEquals("10", idle.getHeadEntryId());
    assertEquals("RUNNABLE", runnable.getStatus());
    assertEquals("11", runnable.getHeadEntryId());
  }
}
