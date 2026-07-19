package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.time.Instant;
import java.time.LocalDateTime;

class HarnessThreadDtoConverterTest {
  private final HarnessThreadDtoConverter converter = new HarnessThreadDtoConverter();

  /** Public input types remain enum names for both runtime and persistence projections. */
  @Test
  void convertsInputTypesToUppercaseEnumNames() {
    ThreadInput runtimeInput =
        new ThreadInput(
            1L,
            2L,
            1L,
            ThreadInputType.SET_AGENT,
            "{\"agentDefinitionId\":1,\"agentName\":\"default-assistant\"}",
            "runtime-key",
            ThreadInputStatus.QUEUED,
            null,
            null,
            null,
            Instant.EPOCH);
    HarnessThreadInputDO persistedInput = new HarnessThreadInputDO();
    persistedInput.setId(3L);
    persistedInput.setThreadId(2L);
    persistedInput.setSequence(2L);
    persistedInput.setInputType("set_yolo");
    persistedInput.setPayloadJson("{\"yoloEnabled\":true}");
    persistedInput.setClientMessageId("persisted-key");
    persistedInput.setStatus("queued");
    persistedInput.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));

    assertEquals("SET_AGENT", converter.convert(runtimeInput).getInputType());
    assertEquals("SET_YOLO", converter.convert(persistedInput).getInputType());
  }
}
