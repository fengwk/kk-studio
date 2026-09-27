package fun.fengwk.kkstudio.web.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueService.StageBudgetView;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentThreadDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueEvidenceDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueRunSummaryDTO;
import fun.fengwk.kkstudio.share.project.IssueStageBudgetDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;
import fun.fengwk.kkstudio.share.project.ProjectWorkflowDTO;
import fun.fengwk.kkstudio.share.project.ProjectWorkflowStateDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Project 与 Issue 领域模型与公开 wire DTO 之间的权威双向转换器。 */
@Component
public class ProjectDtoMapper {

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DECIMAL_LONG_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)$");

  private final ProjectWorkflowJsonCodec workflowCodec;
  private final ObjectMapper objectMapper;

  public ProjectDtoMapper(ProjectWorkflowJsonCodec workflowCodec, ObjectMapper objectMapper) {
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ProjectDtoMapper() {
    this(new ProjectWorkflowJsonCodec(), new ObjectMapper());
  }

  public static UUID parseUuid(String value, String fieldName) {
    if (value == null || !UUID_PATTERN.matcher(value.trim()).matches()) {
      throw new IllegalArgumentException(fieldName + " must be a valid UUID string");
    }
    try {
      return UUID.fromString(value.trim().toLowerCase());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(fieldName + " must be a valid UUID string");
    }
  }

  public static long parseNonNegativeLong(String value, String fieldName) {
    if (value == null || !DECIMAL_LONG_PATTERN.matcher(value.trim()).matches()) {
      throw new IllegalArgumentException(
          fieldName + " must be a canonical non-negative decimal string");
    }
    try {
      long parsed = Long.parseLong(value.trim());
      if (parsed < 0) {
        throw new IllegalArgumentException(fieldName + " must be non-negative");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(fieldName + " exceeds long range");
    }
  }

  public static String formatUuid(UUID uuid) {
    return uuid == null ? null : uuid.toString().toLowerCase();
  }

  public static String formatLong(Long value) {
    return value == null ? null : String.valueOf(value);
  }

  public static String formatInstant(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  public ProjectWorkflow toWorkflow(ProjectWorkflowDTO dto) {
    Objects.requireNonNull(dto, "dto");
    if (dto.getStates() == null) {
      throw new IllegalArgumentException("workflow states must not be null");
    }
    List<ProjectWorkflowState> states = new ArrayList<>();
    for (ProjectWorkflowStateDTO stateDto : dto.getStates()) {
      if (stateDto == null) {
        throw new IllegalArgumentException("workflow state must not be null");
      }
      ProjectStateCode stateCode = ProjectStateCode.of(stateDto.getState());
      String name = stateDto.getName();
      String agent = stateDto.getAgent();
      String environment = stateDto.getEnvironment();
      String instructions = stateDto.getInstructions();
      Integer maxRuns = null;
      if (stateDto.getMaxRuns() != null && !stateDto.getMaxRuns().isBlank()) {
        maxRuns = (int) parseNonNegativeLong(stateDto.getMaxRuns(), "maxRuns");
      }
      boolean enabled = stateDto.getEnabled() == null || stateDto.getEnabled();
      List<ProjectStateCode> next =
          stateDto.getNext() == null
              ? List.of()
              : stateDto.getNext().stream().map(ProjectStateCode::of).toList();
      states.add(
          new ProjectWorkflowState(
              stateCode, name, agent, environment, instructions, maxRuns, enabled, next));
    }
    return new ProjectWorkflow(states);
  }

  public ProjectWorkflowDTO toWorkflowDto(ProjectWorkflow workflow) {
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
                        .maxRuns(state.maxRuns() != null ? String.valueOf(state.maxRuns()) : null)
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
        .nextIssueNumber(formatLong(project.getNextIssueNumber()))
        .version(formatLong(project.getVersion()))
        .archivedAt(formatInstant(project.getArchivedAt()))
        .createdAt(formatInstant(project.getCreatedAt()))
        .updatedAt(formatInstant(project.getUpdatedAt()))
        .build();
  }

  public IssueDTO toDto(Issue issue) {
    Objects.requireNonNull(issue, "issue");
    return IssueDTO.builder()
        .id(formatUuid(issue.getId()))
        .projectId(formatUuid(issue.getProjectId()))
        .number(formatLong(issue.getNumber()))
        .title(issue.getTitle())
        .description(issue.getDescription())
        .state(issue.getState())
        .blockedFromState(issue.getBlockedFromState())
        .blockReason(issue.getBlockReason())
        .pauseReason(issue.getPauseReason())
        .pauseDetail(issue.getPauseDetail())
        .version(formatLong(issue.getVersion()))
        .archivedAt(formatInstant(issue.getArchivedAt()))
        .createdAt(formatInstant(issue.getCreatedAt()))
        .updatedAt(formatInstant(issue.getUpdatedAt()))
        .build();
  }

  public IssueActivityDTO toDto(IssueActivity activity) {
    Objects.requireNonNull(activity, "activity");
    return IssueActivityDTO.builder()
        .issueId(formatUuid(activity.getIssueId()))
        .sequence(formatLong(activity.getSequence()))
        .kind(activity.getKind() != null ? activity.getKind().name() : null)
        .actorType(activity.getActorType() != null ? activity.getActorType().name() : null)
        .actorAgentName(activity.getActorAgentName())
        .runId(formatUuid(activity.getRunId()))
        .body(activity.getBody())
        .data(decodeJsonData(activity.getData()))
        .createdAt(formatInstant(activity.getCreatedAt()))
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
        .createdAt(formatInstant(evidence.getCreatedAt()))
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
        .budgetAfterOrdinal(formatLong(budget.budgetAfterOrdinal()))
        .usedRuns(formatLong(budget.usedRuns()))
        .remainingRuns(formatLong(budget.remainingRuns()))
        .build();
  }

  public IssueRunSummaryDTO toSummaryDto(IssueRun run) {
    return toSummaryDto(run, null);
  }

  public IssueRunSummaryDTO toSummaryDto(IssueRun run, String agentName) {
    if (run == null) {
      return null;
    }
    return IssueRunSummaryDTO.builder()
        .id(formatUuid(run.getId()))
        .issueId(formatUuid(run.getIssueId()))
        .ordinal(formatLong(run.getOrdinal()))
        .state(run.getState())
        .status(run.getStatus() != null ? run.getStatus().name() : null)
        .agentName(agentName)
        .startedAt(formatInstant(run.getStartedAt()))
        .endedAt(formatInstant(run.getEndedAt()))
        .build();
  }

  public IssueRunDTO toDto(IssueRun run) {
    return toDto(run, null);
  }

  public IssueRunDTO toDto(IssueRun run, String agentName) {
    if (run == null) {
      return null;
    }
    return IssueRunDTO.builder()
        .id(formatUuid(run.getId()))
        .issueId(formatUuid(run.getIssueId()))
        .ordinal(formatLong(run.getOrdinal()))
        .state(run.getState())
        .agentName(agentName)
        .sessionId(formatUuid(run.getSessionId()))
        .threadId(formatUuid(run.getThreadId()))
        .status(run.getStatus() != null ? run.getStatus().name() : null)
        .startEntryId(formatUuid(run.getStartEntryId()))
        .endEntryId(formatUuid(run.getEndEntryId()))
        .finalAnswerEntryId(formatUuid(run.getFinalAnswerEntryId()))
        .nextState(run.getNextState())
        .observedActivitySequence(formatLong(run.getObservedActivitySequence()))
        .remainingExecutionMs(formatLong(run.getRemainingExecutionMs()))
        .error(run.getError())
        .version(formatLong(run.getVersion()))
        .startedAt(formatInstant(run.getStartedAt()))
        .endedAt(formatInstant(run.getEndedAt()))
        .build();
  }

  private Object decodeJsonData(String data) {
    if (data == null || data.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(data, Object.class);
    } catch (JsonProcessingException error) {
      return null;
    }
  }
}
