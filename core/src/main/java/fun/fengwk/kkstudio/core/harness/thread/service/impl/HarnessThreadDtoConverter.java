package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadViewDO;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class HarnessThreadDtoConverter {

  public HarnessThreadDTO convert(AgentThread thread) {
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(Long.toString(thread.id()));
    dto.setSessionId(Long.toString(thread.sessionId()));
    dto.setHeadEntryId(Long.toString(thread.headEntryId()));
    dto.setStatus(thread.status().value());
    dto.setInputSequence(thread.inputSequence());
    dto.setProcessing(thread.isProcessing(Instant.now()));
    dto.setCreateTime(LocalDateTime.ofInstant(thread.createdAt(), ZoneOffset.UTC));
    dto.setUpdateTime(LocalDateTime.ofInstant(thread.updatedAt(), ZoneOffset.UTC));
    return dto;
  }

  public HarnessThreadDTO convert(HarnessThreadDO row) {
    return convert(row, null);
  }

  public HarnessThreadDTO convert(HarnessThreadViewDO row) {
    return convert(row, row.getSessionTitle());
  }

  public HarnessThreadDTO convert(HarnessThreadDO row, String sessionTitle) {
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(Long.toString(row.getId()));
    dto.setSessionId(Long.toString(row.getSessionId()));
    dto.setSessionTitle(sessionTitle);
    dto.setHeadEntryId(Long.toString(row.getHeadEntryId()));
    dto.setStatus(row.getStatus());
    dto.setInputSequence(row.getInputSequence());
    boolean processing =
        row.getProcessorToken() != null
            && row.getProcessorUntil() != null
            && row.getProcessorUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC));
    dto.setProcessing(processing);
    dto.setCreateTime(row.getCreateTime());
    dto.setUpdateTime(row.getUpdateTime());
    return dto;
  }

  public HarnessThreadInputDTO convert(ThreadInput input) {
    HarnessThreadInputDTO dto = new HarnessThreadInputDTO();
    dto.setInputId(Long.toString(input.id()));
    dto.setThreadId(Long.toString(input.threadId()));
    dto.setSequence(input.sequence());
    dto.setInputType(input.inputType().value());
    dto.setPayloadJson(input.payloadJson());
    dto.setClientMessageId(input.clientMessageId());
    if (input.appliedEntryId() != null) {
      dto.setAppliedEntryId(Long.toString(input.appliedEntryId()));
    }
    if (input.resolvedAt() != null) {
      dto.setResolvedAt(LocalDateTime.ofInstant(input.resolvedAt(), ZoneOffset.UTC));
    }
    dto.setStatus(input.status().value());
    if (input.cancelledByStopId() != null) {
      dto.setCancelledByStopId(Long.toString(input.cancelledByStopId()));
    }
    dto.setCreateTime(LocalDateTime.ofInstant(input.createdAt(), ZoneOffset.UTC));
    return dto;
  }

  public HarnessThreadInputDTO convert(HarnessThreadInputDO row) {
    HarnessThreadInputDTO dto = new HarnessThreadInputDTO();
    dto.setInputId(Long.toString(row.getId()));
    dto.setThreadId(Long.toString(row.getThreadId()));
    dto.setSequence(row.getSequence());
    dto.setInputType(row.getInputType());
    dto.setPayloadJson(row.getPayloadJson());
    dto.setClientMessageId(row.getClientMessageId());
    if (row.getAppliedEntryId() != null) {
      dto.setAppliedEntryId(Long.toString(row.getAppliedEntryId()));
    }
    dto.setStatus(row.getStatus());
    dto.setResolvedAt(row.getResolvedAt());
    if (row.getCancelledByStopId() != null) {
      dto.setCancelledByStopId(Long.toString(row.getCancelledByStopId()));
    }
    dto.setCreateTime(row.getCreateTime());
    return dto;
  }

  public ThreadEventDTO convert(HarnessThreadEventDO row) {
    ThreadEventDTO dto = new ThreadEventDTO();
    dto.setEventId(Long.toString(row.getId()));
    dto.setThreadId(Long.toString(row.getThreadId()));
    if (row.getSubjectEntryId() != null) {
      dto.setSubjectEntryId(Long.toString(row.getSubjectEntryId()));
    }
    dto.setEventType(row.getEventType());
    dto.setPayloadJson(row.getPayloadJson());
    dto.setCreateTime(row.getCreateTime());
    return dto;
  }

  public HarnessSessionEntryDTO convert(HarnessSessionEntryDO row) {
    HarnessSessionEntryDTO dto = new HarnessSessionEntryDTO();
    dto.setEntryId(Long.toString(row.getId()));
    dto.setSessionId(Long.toString(row.getSessionId()));
    if (row.getParentEntryId() != null) {
      dto.setParentEntryId(Long.toString(row.getParentEntryId()));
    }
    dto.setEntryType(row.getEntryType());
    dto.setPayloadJson(row.getPayloadJson());
    dto.setCreateTime(row.getCreateTime());
    return dto;
  }
}
