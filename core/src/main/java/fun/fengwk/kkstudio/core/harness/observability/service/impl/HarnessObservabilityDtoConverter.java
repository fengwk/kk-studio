package fun.fengwk.kkstudio.core.harness.observability.service.impl;

import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskReportDTO;
import fun.fengwk.kkstudio.share.model.ToolArtifactRefDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps the persistent observability projections to share DTOs. */
@Component
public class HarnessObservabilityDtoConverter {

  public RunEventDTO convert(RunEvent source) {
    if (source == null) {
      return null;
    }
    RunEventDTO target = new RunEventDTO();
    target.setEventId(Long.toString(source.sequence()));
    target.setRunId(HarnessIds.format(source.runId()));
    target.setSequence(source.sequence());
    target.setType(source.type().value());
    target.setPayloadJson(source.payloadJson());
    target.setCreateTime(source.createdAt());
    return target;
  }

  public RootActivityDTO convert(RootActivity source) {
    if (source == null) {
      return null;
    }
    RootActivityDTO target = new RootActivityDTO();
    target.setRootSessionId(HarnessIds.format(source.rootSessionId()));
    target.setSessionId(HarnessIds.format(source.sessionId()));
    target.setRunId(HarnessIds.format(source.runId()));
    target.setEventId(Long.toString(source.eventId()));
    target.setSequence(source.sequence());
    target.setType(source.type().value());
    target.setPayloadJson(source.payloadJson());
    target.setCreateTime(source.createdAt());
    return target;
  }

  public ToolInvocationDTO convert(ToolInvocation source) {
    if (source == null) {
      return null;
    }
    ToolInvocationDTO target = new ToolInvocationDTO();
    target.setId(HarnessIds.format(source.id()));
    target.setRunId(HarnessIds.format(source.runId()));
    target.setAssistantEntryId(HarnessIds.format(source.assistantEntryId()));
    target.setOrdinal(source.ordinal());
    target.setToolCallId(source.toolCallId());
    target.setToolName(source.toolName());
    target.setToolVersion(source.toolVersion());
    target.setTargetType(source.targetType().name());
    target.setEnvironmentId(
        source.environmentId() == null ? null : HarnessIds.format(source.environmentId()));
    target.setArgumentsJson(source.argumentsJson());
    target.setStatus(source.status().name());
    target.setPermissionAction(source.permissionAction().name());
    target.setPermissionDecision(
        source.permissionDecision() == null ? null : source.permissionDecision().name());
    target.setDeadlineAt(source.deadlineAt());
    target.setLeaseOwner(source.leaseOwner());
    target.setLeaseUntil(source.leaseUntil());
    target.setCancelRequestedAt(source.cancelRequestedAt());
    target.setResultJson(source.resultJson());
    target.setErrorMessage(source.errorMessage());
    target.setCreateTime(source.createdAt());
    target.setStartedAt(source.startedAt());
    target.setFinishedAt(source.finishedAt());
    target.setUpdateTime(source.updatedAt());
    return target;
  }

  public SubagentTaskDTO convert(HarnessSubagentTaskDO source) {
    if (source == null) {
      return null;
    }
    SubagentTaskDTO target = new SubagentTaskDTO();
    target.setParentInvocationId(HarnessIds.format(source.getParentInvocationId()));
    target.setParentSessionId(HarnessIds.format(source.getParentSessionId()));
    target.setChildSessionId(HarnessIds.format(source.getChildSessionId()));
    target.setChildRunId(HarnessIds.format(source.getChildRunId()));
    target.setTargetAgent(source.getTargetAgent());
    target.setWorkingCopyPolicy(source.getWorkingCopyPolicy());
    target.setWorkingCopyRevision(source.getWorkingCopyRevision());
    target.setMaxTurns(source.getMaxTurns());
    target.setIdleTimeoutMillis(source.getIdleTimeoutMillis());
    target.setStatus(source.getStatus());
    target.setReport(parseReport(source.getReportJson()));
    target.setCreateTime(instant(source.getCreateTime()));
    target.setUpdateTime(instant(source.getUpdateTime()));
    return target;
  }

  private static SubagentTaskReportDTO parseReport(String reportJson) {
    if (reportJson == null || reportJson.isBlank()) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(reportJson);
      if (root == null || !root.isObject()) {
        return null;
      }
      SubagentTaskReportDTO target = new SubagentTaskReportDTO();
      target.setChildSessionId(textOrNull(root, "childSessionId"));
      target.setChildRunId(textOrNull(root, "childRunId"));
      target.setStatus(textOrNull(root, "status"));
      target.setFinalReport(textOrNull(root, "finalReport"));
      target.setTurnCount(intOrNull(root, "turnCount"));
      target.setToolCount(intOrNull(root, "toolCount"));
      target.setWorkingCopyPolicy(textOrNull(root, "workingCopyPolicy"));
      target.setWorkingCopyRevision(textOrNull(root, "workingCopyRevision"));
      target.setArtifacts(parseArtifacts(root));
      return target;
    } catch (Exception error) {
      return null;
    }
  }

  private static List<ToolArtifactRefDTO> parseArtifacts(com.fasterxml.jackson.databind.JsonNode root) {
    com.fasterxml.jackson.databind.JsonNode node = root.get("artifacts");
    if (node == null || !node.isArray()) {
      return null;
    }
    List<ToolArtifactRefDTO> refs = new ArrayList<>(node.size());
    for (com.fasterxml.jackson.databind.JsonNode item : node) {
      ToolArtifactRefDTO ref = new ToolArtifactRefDTO();
      ref.setArtifactId(textOrNull(item, "artifactId"));
      ref.setMediaType(textOrNull(item, "mediaType"));
      ref.setSizeBytes(longOrNull(item, "sizeBytes"));
      refs.add(ref);
    }
    return refs;
  }

  private static String textOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
    com.fasterxml.jackson.databind.JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
    com.fasterxml.jackson.databind.JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asInt();
  }

  private static Long longOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
    com.fasterxml.jackson.databind.JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asLong();
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}