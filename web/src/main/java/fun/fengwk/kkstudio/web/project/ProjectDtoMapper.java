package fun.fengwk.kkstudio.web.project;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentSessionDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDependencyDTO;
import fun.fengwk.kkstudio.share.project.IssueEvidenceDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueRunSummaryDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;

import java.time.Instant;
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

  public ProjectDTO toDto(Project project) {
    Objects.requireNonNull(project, "project");
    return ProjectDTO.builder()
        .id(formatUuid(project.getId()))
        .title(project.getTitle())
        .description(project.getDescription())
        .yoloEnabled(project.isYoloEnabled())
        .maxReviewRejections(formatLong((long) project.getMaxReviewRejections()))
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
        .status(issue.getStatus() != null ? issue.getStatus().name() : null)
        .assigneeAgentName(issue.getAssigneeAgentName())
        .reviewerAgentName(issue.getReviewerAgentName())
        .version(formatLong(issue.getVersion()))
        .archivedAt(formatInstant(issue.getArchivedAt()))
        .createdAt(formatInstant(issue.getCreatedAt()))
        .updatedAt(formatInstant(issue.getUpdatedAt()))
        .build();
  }

  public IssueDependencyDTO toDto(IssueDependency dep) {
    Objects.requireNonNull(dep, "dep");
    return IssueDependencyDTO.builder()
        .issueId(formatUuid(dep.getIssueId()))
        .dependsOnIssueId(formatUuid(dep.getDependsOnIssueId()))
        .projectId(formatUuid(dep.getProjectId()))
        .createdAt(formatInstant(dep.getCreatedAt()))
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
        .targetRole(activity.getTargetRole() != null ? activity.getTargetRole().name() : null)
        .runId(formatUuid(activity.getRunId()))
        .submissionRunId(formatUuid(activity.getSubmissionRunId()))
        .decision(activity.getDecision() != null ? activity.getDecision().name() : null)
        .body(activity.getBody())
        .idempotencyKey(activity.getIdempotencyKey())
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
        .origin(evidence.getOrigin() != null ? evidence.getOrigin().name() : null)
        .name(evidence.getName())
        .runId(formatUuid(evidence.getRunId()))
        .publishedAt(formatInstant(evidence.getCreatedAt()))
        .build();
  }

  /** 投影 Issue + Agent 的稳定归属；{@code role} 由 Issue 当前职责配置推导。 */
  public IssueAgentSessionDTO toDto(IssueAgentSession agentSession, String role) {
    if (agentSession == null) {
      return null;
    }
    return IssueAgentSessionDTO.builder()
        .id(formatUuid(agentSession.getId()))
        .issueId(formatUuid(agentSession.getIssueId()))
        .agentName(agentSession.getAgentName())
        .role(role)
        .sessionId(formatUuid(agentSession.getSessionId()))
        .branchId(formatUuid(agentSession.getThreadId()))
        .createdAt(formatInstant(agentSession.getCreatedAt()))
        .build();
  }

  public IssueRunSummaryDTO toSummaryDto(IssueRun run) {
    if (run == null) {
      return null;
    }
    return IssueRunSummaryDTO.builder()
        .id(formatUuid(run.getId()))
        .issueId(formatUuid(run.getIssueId()))
        .ordinal(formatLong(run.getOrdinal()))
        .role(run.getRole() != null ? run.getRole().name() : null)
        .agentName(run.getAgentName())
        .submissionRunId(formatUuid(run.getSubmissionRunId()))
        .status(run.getStatus() != null ? run.getStatus().name() : null)
        .outcome(run.getOutcome() != null ? run.getOutcome().name() : null)
        .waitingReason(run.getWaitingReason())
        .createdAt(formatInstant(run.getCreatedAt()))
        .completedAt(formatInstant(run.getCompletedAt()))
        .build();
  }

  /**
   * 完整 Run 投影：Session 与归属取自同一 {@code (issueId, agentName)} 的稳定 {@link IssueAgentSession}， 因此同一
   * Agent 的多次 Run 共享 sessionId，权限则随当前 Run 变化。
   */
  public IssueRunDTO toDetailDto(IssueRun run, IssueAgentSession agentSession) {
    if (run == null) {
      return null;
    }
    return IssueRunDTO.builder()
        .id(formatUuid(run.getId()))
        .issueId(formatUuid(run.getIssueId()))
        .ordinal(formatLong(run.getOrdinal()))
        .role(run.getRole() != null ? run.getRole().name() : null)
        .agentName(run.getAgentName())
        .agentSessionId(agentSession != null ? formatUuid(agentSession.getId()) : null)
        .sessionId(agentSession != null ? formatUuid(agentSession.getSessionId()) : null)
        .submissionRunId(formatUuid(run.getSubmissionRunId()))
        .status(run.getStatus() != null ? run.getStatus().name() : null)
        .outcome(run.getOutcome() != null ? run.getOutcome().name() : null)
        .observedActivitySequence(formatLong(run.getObservedActivitySequence()))
        .continuationCount(run.getContinuationCount())
        .maxContinuations(run.getMaxContinuations())
        .deadline(formatInstant(run.getDeadline()))
        .waitingReason(run.getWaitingReason())
        .result(run.getResult())
        .terminalActionId(run.getTerminalActionId())
        .version(formatLong(run.getVersion()))
        .createdAt(formatInstant(run.getCreatedAt()))
        .updatedAt(formatInstant(run.getUpdatedAt()))
        .completedAt(formatInstant(run.getCompletedAt()))
        .build();
  }
}
