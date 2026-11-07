package fun.fengwk.kkstudio.project.dto;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.StageBudgetView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Project 与 Issue 领域模型与公开 wire DTO 之间的权威双向转换器。 */
@Component
public class ProjectDtoMapper {

  private final ProjectWorkflowJsonCodec workflowCodec;
  private final ObjectMapper objectMapper;

  public ProjectDtoMapper(ProjectWorkflowJsonCodec workflowCodec, ObjectMapper objectMapper) {
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  private static String formatUuid(UUID uuid) {
    return uuid == null ? null : uuid.toString().toLowerCase();
  }

  public ProjectWorkflow toWorkflow(ProjectWorkflowDTO dto) {
    if (dto == null) {
      throw DomainException.validation("workflow", "workflow is required");
    }
    if (dto.getStates() == null) {
      throw DomainException.validation("workflow", "workflow states must not be null");
    }
    List<ProjectWorkflowState> states = new ArrayList<>();
    for (ProjectWorkflowStateDTO stateDto : dto.getStates()) {
      if (stateDto == null) {
        throw DomainException.validation("workflow", "workflow state must not be null");
      }
      ProjectStateCode stateCode = requireWorkflowStateCode(stateDto.getState(), "state");
      String name = stateDto.getName();
      String agent = stateDto.getAgent();
      String environment = stateDto.getEnvironment();
      String instructions = stateDto.getInstructions();
      Integer maxRuns = parseMaxRuns(stateDto.getMaxRuns());
      boolean enabled = stateDto.getEnabled() == null || stateDto.getEnabled();
      List<ProjectStateCode> next =
          stateDto.getNext() == null
              ? List.of()
              : stateDto.getNext().stream()
                  .map(value -> requireWorkflowStateCode(value, "next"))
                  .toList();
      states.add(
          new ProjectWorkflowState(
              stateCode, name, agent, environment, instructions, maxRuns, enabled, next));
    }
    return new ProjectWorkflow(states);
  }

  /** maxRuns 是 64 位整数 DTO 字段：只校验领域可容纳的范围，wire 解析由 HTTP 边界负责。 */
  private static Integer parseMaxRuns(Long value) {
    if (value == null) {
      return null;
    }
    if (value < 1 || value > Integer.MAX_VALUE) {
      throw DomainException.validation("maxRuns", "maxRuns must be in range [1, 2147483647]");
    }
    return (int) (long) value;
  }

  private static ProjectStateCode requireWorkflowStateCode(String value, String field) {
    if (value == null) {
      throw DomainException.validation("workflow", "workflow " + field + " must not be null");
    }
    return ProjectStateCode.of(value);
  }

  ProjectWorkflowDTO toWorkflowDto(ProjectWorkflow workflow) {
    if (workflow == null) {
      return null;
    }
    List<ProjectWorkflowStateDTO> states =
        workflow.states().stream()
            .map(
                state ->
                    ProjectWorkflowStateDTO.builder()
                        .state(state.state().value())
                        .name(state.name())
                        .agent(state.agent())
                        .environment(state.environment())
                        .instructions(state.instructions())
                        .maxRuns(state.maxRuns() != null ? state.maxRuns().longValue() : null)
                        .enabled(state.enabled())
                        .next(state.next().stream().map(ProjectStateCode::value).toList())
                        .build())
            .toList();
    return ProjectWorkflowDTO.builder().states(states).build();
  }

  public ProjectDTO toDto(Project project) {
    Objects.requireNonNull(project, "project");
    ProjectWorkflowDTO workflowDto = null;
    if (project.getWorkflowJson() != null && !project.getWorkflowJson().isBlank()) {
      ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
      workflowDto = toWorkflowDto(workflow);
    }
    return ProjectDTO.builder()
        .id(formatUuid(project.getId()))
        .title(project.getTitle())
        .description(project.getDescription())
        .workflow(workflowDto)
        .yoloEnabled(project.isYoloEnabled())
        .nextIssueNumber(project.getNextIssueNumber())
        .version(project.getVersion())
        .archivedAt(project.getArchivedAt())
        .createdAt(project.getCreatedAt())
        .updatedAt(project.getUpdatedAt())
        .build();
  }

  public IssueDTO toDto(Issue issue) {
    Objects.requireNonNull(issue, "issue");
    return IssueDTO.builder()
        .id(formatUuid(issue.getId()))
        .projectId(formatUuid(issue.getProjectId()))
        .number(issue.getNumber())
        .title(issue.getTitle())
        .description(issue.getDescription())
        .state(issue.getState())
        .blockedFromState(issue.getBlockedFromState())
        .blockReason(issue.getBlockReason())
        .pauseReason(
            issue.getPauseReason() == null ? null : PauseReason.valueOf(issue.getPauseReason()))
        .pauseDetail(issue.getPauseDetail())
        .version(issue.getVersion())
        .createdAt(issue.getCreatedAt())
        .updatedAt(issue.getUpdatedAt())
        .build();
  }

  public IssueActivityDTO toDto(IssueActivity activity) {
    Objects.requireNonNull(activity, "activity");
    return IssueActivityDTO.builder()
        .issueId(formatUuid(activity.getIssueId()))
        .sequence(activity.getSequence())
        .kind(activity.getKind())
        .actorType(activity.getActorType())
        .actorAgentName(activity.getActorAgentName())
        .runId(formatUuid(activity.getRunId()))
        .body(activity.getBody())
        .data(decodeJsonData(activity.getData()))
        .createdAt(activity.getCreatedAt())
        .build();
  }

  /** 投影 Issue 已发布证据：URI 由 blob id 派生，绝不持久化；它是资源标识，不是读取凭据。 */
  public IssueEvidenceDTO toDto(IssueEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    return IssueEvidenceDTO.builder()
        .issueId(formatUuid(evidence.getIssueId()))
        .blobId(formatUuid(evidence.getBlobId()))
        .uri(SessionResourceUri.format(evidence.getBlobId()))
        .name(evidence.getName())
        .actorAgentName(evidence.getActorAgentName())
        .runId(formatUuid(evidence.getRunId()))
        .createdAt(evidence.getCreatedAt())
        .build();
  }

  public IssueAgentThreadDTO toDto(IssueAgentThread agentThread) {
    Objects.requireNonNull(agentThread, "agentThread");
    return IssueAgentThreadDTO.builder()
        .issueId(formatUuid(agentThread.issueId()))
        .agentName(agentThread.agentName())
        .threadId(formatUuid(agentThread.threadId()))
        .build();
  }

  public IssueStageBudgetDTO toDto(StageBudgetView budget) {
    Objects.requireNonNull(budget, "budget");
    return IssueStageBudgetDTO.builder()
        .state(budget.state())
        .maxRuns(budget.maxRuns())
        .budgetAfterOrdinal(budget.budgetAfterOrdinal())
        .usedRuns(budget.usedRuns())
        .remainingRuns(budget.remainingRuns())
        .build();
  }

  public IssueRunSummaryDTO toSummaryDto(IssueRun run, String agentName) {
    if (run == null) {
      return null;
    }
    return IssueRunSummaryDTO.builder()
        .id(formatUuid(run.getId()))
        .issueId(formatUuid(run.getIssueId()))
        .ordinal(run.getOrdinal())
        .state(run.getState())
        .status(run.getStatus())
        .agentName(agentName)
        .startedAt(run.getStartedAt())
        .endedAt(run.getEndedAt())
        .build();
  }

  public IssueRunDTO toDto(IssueRun run, String agentName) {
    if (run == null) {
      return null;
    }
    return IssueRunDTO.builder()
        .id(formatUuid(run.getId()))
        .issueId(formatUuid(run.getIssueId()))
        .ordinal(run.getOrdinal())
        .state(run.getState())
        .agentName(agentName)
        .sessionId(formatUuid(run.getSessionId()))
        .threadId(formatUuid(run.getThreadId()))
        .status(run.getStatus())
        .startEntryId(formatUuid(run.getStartEntryId()))
        .endEntryId(formatUuid(run.getEndEntryId()))
        .finalAnswerEntryId(formatUuid(run.getFinalAnswerEntryId()))
        .nextState(run.getNextState())
        .observedActivitySequence(run.getObservedActivitySequence())
        .remainingExecutionMs(run.getRemainingExecutionMs())
        .error(run.getError())
        .version(run.getVersion())
        .startedAt(run.getStartedAt())
        .endedAt(run.getEndedAt())
        .build();
  }

  private JsonNode decodeJsonData(String data) {
    if (data == null || data.isBlank()) {
      return null;
    }
    try {
      // 自由 JSON 以节点保留数字形态，不受 DTO 的 Long 字符串序列化规则影响。
      return objectMapper
          .readerFor(JsonNode.class)
          .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readValue(data);
    } catch (JacksonException error) {
      return null;
    }
  }
}
