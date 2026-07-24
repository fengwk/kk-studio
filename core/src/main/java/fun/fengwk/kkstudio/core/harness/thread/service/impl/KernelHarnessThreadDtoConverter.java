package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.kernel.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** final Kernel command records 的 DTO 投影。 */
@Component
public class KernelHarnessThreadDtoConverter {

  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();

  public HarnessThreadDTO convert(HarnessThread thread) {
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(Long.toString(thread.id()));
    dto.setSessionId(Long.toString(thread.sessionId()));
    dto.setHeadEntryId(Long.toString(thread.headEntryId()));
    dto.setStatus(thread.runnable() ? "RUNNABLE" : "IDLE");
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
}
