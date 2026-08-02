package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

/** Verifies the bound Thread projection and its stateless query shape. */
class HarnessThreadDtoConverterTest {

  private static final Instant CREATED_AT = Instant.parse("2026-07-24T00:00:00Z");
  private static final Instant UPDATED_AT = Instant.parse("2026-07-24T00:00:01Z");

  @Test
  void projectsBoundIdleAndRunnableStates() {
    HarnessThreadDtoConverter converter = new HarnessThreadDtoConverter();

    HarnessThreadDTO idle =
        converter.convert(
            new HarnessThread(1L, 10L, 0L, false, 0L, 0L, null, CREATED_AT, UPDATED_AT));
    HarnessThreadDTO runnable =
        converter.convert(
            new HarnessThread(2L, 11L, 3L, true, 1L, 4L, null, CREATED_AT, UPDATED_AT));

    assertEquals("IDLE", idle.getStatus());
    assertEquals("10", idle.getHeadEntryId());
    assertEquals("0", idle.getRevision());
    assertEquals("RUNNABLE", runnable.getStatus());
    assertEquals("11", runnable.getHeadEntryId());
    assertEquals(3L, runnable.getInputSequence());
  }

  @Test
  void queryProjectionContainsNoActiveRuntimeConfigurationFields() {
    Set<String> obsoleteFields =
        Set.of(
            "activeAgentDefinitionId",
            "activeAgentName",
            "activeEnvironmentName",
            "modelId",
            "variant",
            "yoloEnabled");

    assertFalse(
        Arrays.stream(HarnessThreadDTO.class.getDeclaredFields())
            .map(field -> field.getName())
            .anyMatch(obsoleteFields::contains));
  }
}
