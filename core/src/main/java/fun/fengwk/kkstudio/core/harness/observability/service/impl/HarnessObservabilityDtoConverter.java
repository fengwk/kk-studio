package fun.fengwk.kkstudio.core.harness.observability.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.tool.worker.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.task.TaskReport;
import fun.fengwk.kkstudio.harness.runtime.task.TaskResultFormatter;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskReportDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;
import fun.fengwk.kkstudio.share.model.ToolArtifactRefDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.time.ZoneOffset;
import java.util.List;

@Component
public class HarnessObservabilityDtoConverter {

  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();

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

  public RootActivityDTO convert(RootActivity activity) {
    RootActivityDTO dto = new RootActivityDTO();
    dto.setRootSessionId(Long.toString(activity.rootSessionId()));
    dto.setSessionId(Long.toString(activity.sessionId()));
    dto.setThreadId(Long.toString(activity.threadId()));
    dto.setEventId(Long.toString(activity.eventId()));
    dto.setEventType(activity.type().value());
    dto.setPayloadJson(activity.payloadJson());
    dto.setCreateTime(activity.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
    return dto;
  }

  public SubagentTaskDTO convert(HarnessSubagentTaskDO row) {
    SubagentTaskDTO dto = new SubagentTaskDTO();
    dto.setParentInvocationId(Long.toString(row.getParentInvocationId()));
    dto.setParentSessionId(Long.toString(row.getParentSessionId()));
    if (row.getParentThreadId() != null) {
      dto.setParentThreadId(Long.toString(row.getParentThreadId()));
    }
    dto.setChildSessionId(Long.toString(row.getChildSessionId()));
    if (row.getChildThreadId() != null) {
      dto.setChildThreadId(Long.toString(row.getChildThreadId()));
    }
    dto.setTargetAgent(row.getTargetAgent());
    dto.setWorkingCopyPolicy(row.getWorkingCopyPolicy());
    dto.setWorkingCopyRevision(row.getWorkingCopyRevision());
    dto.setMaxTurns(row.getMaxTurns());
    dto.setStatus(row.getStatus());
    if (row.getReportJson() != null && !row.getReportJson().isBlank()) {
      dto.setReport(convertReport(TaskResultFormatter.decodeJson(row.getReportJson())));
    }
    if (row.getCreateTime() != null) {
      dto.setCreateTime(row.getCreateTime().toInstant(ZoneOffset.UTC));
    }
    if (row.getUpdateTime() != null) {
      dto.setUpdateTime(row.getUpdateTime().toInstant(ZoneOffset.UTC));
    }
    return dto;
  }

  private SubagentTaskReportDTO convertReport(TaskReport report) {
    SubagentTaskReportDTO dto = new SubagentTaskReportDTO();
    dto.setChildSessionId(Long.toString(report.childSessionId()));
    dto.setChildThreadId(Long.toString(report.childThreadId()));
    dto.setStatus(report.terminalState().name());
    dto.setFinalReport(report.finalAssistantReport());
    dto.setTurnCount(report.turnCount());
    dto.setToolCount(report.toolCount());
    dto.setWorkingCopyPolicy(report.workingCopyPolicy().name());
    dto.setWorkingCopyRevision(report.workingCopyRevision());
    dto.setArtifacts(toArtifactDtos(report.artifacts()));
    return dto;
  }

  private List<ToolArtifactRefDTO> toArtifactDtos(List<ArtifactRef> artifacts) {
    if (artifacts == null || artifacts.isEmpty()) {
      return List.of();
    }
    return artifacts.stream()
        .map(
            ref -> {
              ToolArtifactRefDTO dto = new ToolArtifactRefDTO();
              dto.setArtifactId(ref.artifactId());
              dto.setMediaType(ref.mediaType());
              dto.setSizeBytes(ref.sizeBytes());
              return dto;
            })
        .toList();
  }
}
