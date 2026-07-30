package fun.fengwk.kkstudio.core.ai.runtime.observability.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.runtime.tool.worker.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

/** Observability projection converter for durable ToolInvocation rows. */
@Component
public class HarnessObservabilityDtoConverter {

  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();

  public ToolInvocationDTO convert(ToolInvocationDO row) {
    ToolDescriptor descriptor = TOOL_DESCRIPTOR_CODEC.decode(row.getDescriptorJson());
    ToolInvocationDTO dto = new ToolInvocationDTO();
    dto.setId(Long.toString(row.getId()));
    dto.setThreadId(Long.toString(row.getThreadId()));
    dto.setSessionId(Long.toString(row.getSessionId()));
    dto.setAssistantEntryId(Long.toString(row.getAssistantEntryId()));
    dto.setOrdinal(row.getOrdinal());
    dto.setToolCallId(row.getToolCallId());
    dto.setToolName(descriptor.name());
    dto.setToolVersion(descriptor.version());
    dto.setLocation(row.getLocation());
    dto.setEnvironmentName(row.getEnvironmentName());
    dto.setArgumentsJson(row.getArgumentsJson());
    dto.setExecutionEpoch(row.getExecutionEpoch());
    dto.setStatus(row.getStatus());
    dto.setAttempt(row.getAttempt());
    if (row.getNextAttemptAt() != null) {
      dto.setNextAttemptAt(row.getNextAttemptAt().toInstant());
    }

    if (row.getWorkerUntil() != null) {
      dto.setWorkerUntil(row.getWorkerUntil().toInstant());
    }
    if (row.getDeadlineAt() != null) {
      dto.setDeadlineAt(row.getDeadlineAt().toInstant());
    }
    if (row.getLastActivityAt() != null) {
      dto.setLastActivityAt(row.getLastActivityAt().toInstant());
    }
    dto.setResultJson(row.getResultJson());
    dto.setErrorJson(row.getErrorJson());
    if (row.getAppliedAt() != null) {
      dto.setAppliedAt(row.getAppliedAt().toInstant());
    }
    if (row.getCreatedAt() != null) {
      dto.setCreatedAt(row.getCreatedAt().toInstant());
    }
    if (row.getStartedAt() != null) {
      dto.setStartedAt(row.getStartedAt().toInstant());
    }
    if (row.getFinishedAt() != null) {
      dto.setFinishedAt(row.getFinishedAt().toInstant());
    }
    return dto;
  }
}
