package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;

class PostgresqlEnvironmentToolActivationQueueTest {

  private static final Instant WAKE_AT = Instant.parse("2026-07-24T00:00:00.123456Z");

  @Test
  void rejectsInvalidArgumentsBeforeCallingMapper() {
    EnvironmentToolActivationMapper mapper = mock(EnvironmentToolActivationMapper.class);
    PostgresqlEnvironmentToolActivationQueue queue =
        new PostgresqlEnvironmentToolActivationQueue(mapper);

    assertThrows(NullPointerException.class, () -> queue.activateOldestTool(null, WAKE_AT));
    assertThrows(NullPointerException.class, () -> queue.activateOldestTool("env-a", null));
    assertThrows(IllegalArgumentException.class, () -> queue.activateOldestTool(" ", WAKE_AT));
    assertThrows(IllegalArgumentException.class, () -> queue.activateOldestTool(" env-a", WAKE_AT));
    assertThrows(IllegalArgumentException.class, () -> queue.activateOldestTool("env-a ", WAKE_AT));
    assertThrows(
        IllegalArgumentException.class, () -> queue.activateOldestTool("x".repeat(129), WAKE_AT));
    verify(mapper, never()).activateOldestTool(any(), any(OffsetDateTime.class));
  }

  @Test
  void returnsWhetherMapperActivatedTheQueueHead() {
    EnvironmentToolActivationMapper mapper = mock(EnvironmentToolActivationMapper.class);
    PostgresqlEnvironmentToolActivationQueue queue =
        new PostgresqlEnvironmentToolActivationQueue(mapper);
    OffsetDateTime expectedWakeAt = OffsetDateTime.parse("2026-07-24T00:00:00.123Z");
    when(mapper.activateOldestTool(eq("env-a"), eq(expectedWakeAt))).thenReturn(0, 1);

    assertFalse(queue.activateOldestTool("env-a", WAKE_AT));
    assertTrue(queue.activateOldestTool("env-a", WAKE_AT));

    verify(mapper, times(2)).activateOldestTool("env-a", expectedWakeAt);
  }

  @Test
  void rejectsNullMapper() {
    assertThrows(
        NullPointerException.class, () -> new PostgresqlEnvironmentToolActivationQueue(null));
  }
}
