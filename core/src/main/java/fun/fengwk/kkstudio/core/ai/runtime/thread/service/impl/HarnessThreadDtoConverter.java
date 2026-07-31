package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** final HarnessThread / ThreadInput command records 的 DTO 投影。 */
@Component
public class HarnessThreadDtoConverter {

  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();

  public HarnessThreadDTO convert(HarnessThread thread) {
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(Long.toString(thread.id()));
    dto.setExecutionEpoch(thread.executionEpoch());
    dto.setRevision(Long.toString(thread.revision()));
    dto.setHeadEntryId(thread.headEntryId() == null ? null : Long.toString(thread.headEntryId()));
    dto.setStatus(status(thread));
    dto.setInputSequence(thread.inputSequence());
    dto.setProcessing(thread.hasActiveProcessorAt(Instant.now()));
    dto.setCreateTime(LocalDateTime.ofInstant(thread.createdAt(), ZoneOffset.UTC));
    dto.setUpdateTime(LocalDateTime.ofInstant(thread.updatedAt(), ZoneOffset.UTC));
    return dto;
  }

  public HarnessThreadInputDTO convert(ThreadInput input) {
    HarnessThreadInputDTO dto = new HarnessThreadInputDTO();
    dto.setInputId(Long.toString(input.id()));
    dto.setThreadId(Long.toString(input.threadId()));
    dto.setSequence(input.sequence());
    dto.setInputType(input.type().name());
    dto.setPayloadJson(INPUT_CODEC.encode(input.payload()));
    dto.setClientMessageId(input.idempotencyKey());
    dto.setStatus(input.status().name());
    dto.setResolvedAt(
        input.appliedAt() == null
            ? null
            : LocalDateTime.ofInstant(input.appliedAt(), ZoneOffset.UTC));
    dto.setCreateTime(LocalDateTime.ofInstant(input.createdAt(), ZoneOffset.UTC));
    return dto;
  }

  private static String status(HarnessThread thread) {
    if (thread.runnable()) {
      return "RUNNABLE";
    }
    return thread.headEntryId() == null ? "UNBOUND" : "IDLE";
  }
}
