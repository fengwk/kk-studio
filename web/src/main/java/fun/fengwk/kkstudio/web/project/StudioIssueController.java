package fun.fengwk.kkstudio.web.project;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.project.AddIssueDependencyRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueInputRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CancelIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ChangeIssueStatusRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDependencyDTO;
import fun.fengwk.kkstudio.share.project.IssueDetailDTO;
import fun.fengwk.kkstudio.share.project.IssueInputDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueRunSummaryDTO;
import fun.fengwk.kkstudio.share.project.RetryIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ReviewIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Issue 领域 REST 控制器。 */
@AllArgsConstructor
@RestController
@RequestMapping
public class StudioIssueController {

  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final HarnessOwnerQueryService harnessOwnerQueryService;
  private final ProjectDtoMapper mapper;

  @PostMapping("/api/projects/{projectId}/issues")
  public ResponseEntity<Result<IssueDTO>> createIssue(
      @PathVariable("projectId") String projectIdStr, @RequestBody CreateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    IssueStatus initialStatus =
        request.getInitialStatus() != null && !request.getInitialStatus().isBlank()
            ? parseEnum(request.getInitialStatus(), IssueStatus.class, "initialStatus")
            : null;

    Issue created =
        issueService.createIssue(
            projectId,
            request.getTitle(),
            request.getDescription(),
            request.getAssigneeAgentName(),
            request.getReviewerAgentName(),
            initialStatus);

    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(created)));
  }

  @GetMapping("/api/issues/{issueId}")
  public Result<IssueDetailDTO> getIssue(@PathVariable("issueId") String issueIdStr) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    Issue issue = issueService.getIssue(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    boolean blocked = issueService.isBlocked(issueId);
    List<IssueDependency> deps = issueService.listDependencies(issueId);
    List<IssueInput> inputs = issueService.listInputs(issueId);
    List<IssueRun> runs = issueRunService.listRuns(issueId);

    List<IssueRunDTO> runDtos = new ArrayList<>(runs.size());
    for (IssueRun run : runs) {
      UUID runSessionId = resolveRunSessionId(run.getId());
      runDtos.add(mapper.toDetailDto(run, runSessionId));
    }

    IssueRun activeRun = issueRunService.getActiveRun(issueId);
    IssueRun latestRun = issueRunService.getLatestRun(issueId);

    IssueRunDTO currentRunDto =
        activeRun != null
            ? mapper.toDetailDto(activeRun, resolveRunSessionId(activeRun.getId()))
            : null;
    IssueRunDTO latestRunDto =
        latestRun != null
            ? mapper.toDetailDto(latestRun, resolveRunSessionId(latestRun.getId()))
            : null;

    IssueDetailDTO detail =
        IssueDetailDTO.builder()
            .issue(mapper.toDto(issue))
            .blocked(blocked)
            .dependencies(deps.stream().map(mapper::toDto).toList())
            .inputs(inputs.stream().map(mapper::toDto).toList())
            .runs(runDtos)
            .currentRun(currentRunDto)
            .latestRun(latestRunDto)
            .build();

    return Results.ok(detail);
  }

  @PutMapping("/api/issues/{issueId}")
  public Result<IssueDTO> updateIssue(
      @PathVariable("issueId") String issueIdStr, @RequestBody UpdateIssueRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueService.updateIssue(
            issueId,
            expectedVersion,
            request.getTitle(),
            request.getDescription(),
            request.getAssigneeAgentName(),
            request.getReviewerAgentName());

    return Results.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/status")
  public Result<IssueDTO> changeStatus(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody ChangeIssueStatusRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    if (request.getStatus() == null || request.getStatus().isBlank()) {
      throw new IllegalArgumentException("status must not be blank");
    }
    IssueStatus targetStatus = parseEnum(request.getStatus(), IssueStatus.class, "status");
    Issue updated = issueService.setStatus(issueId, expectedVersion, targetStatus);

    return Results.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/dependencies")
  public ResponseEntity<Result<IssueDependencyDTO>> addDependency(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody AddIssueDependencyRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    UUID dependsOnIssueId =
        ProjectDtoMapper.parseUuid(request.getDependsOnIssueId(), "dependsOnIssueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");

    IssueDependency added = issueService.addDependency(issueId, dependsOnIssueId, expectedVersion);
    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(added)));
  }

  @DeleteMapping("/api/issues/{issueId}/dependencies/{dependsOnIssueId}")
  public ResponseEntity<Void> removeDependency(
      @PathVariable("issueId") String issueIdStr,
      @PathVariable("dependsOnIssueId") String dependsOnIssueIdStr,
      @RequestParam("expectedVersion") String expectedVersionStr) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    UUID dependsOnIssueId = ProjectDtoMapper.parseUuid(dependsOnIssueIdStr, "dependsOnIssueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(expectedVersionStr, "expectedVersion");

    issueService.removeDependency(issueId, dependsOnIssueId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/issues/{issueId}/inputs")
  public ResponseEntity<Result<IssueInputDTO>> appendInput(
      @PathVariable("issueId") String issueIdStr, @RequestBody AppendIssueInputRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    if (request.getBody() == null || request.getBody().isBlank()) {
      throw new IllegalArgumentException("body must not be blank");
    }
    IssueInputKind kind =
        request.getKind() != null && !request.getKind().isBlank()
            ? parseEnum(request.getKind(), IssueInputKind.class, "kind")
            : IssueInputKind.HUMAN;

    IssueInput input =
        issueService.appendInput(
            issueId, kind, request.getBody().trim(), request.getIdempotencyKey());

    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(input)));
  }

  @PostMapping("/api/issues/{issueId}/review")
  public Result<IssueRunSummaryDTO> review(
      @PathVariable("issueId") String issueIdStr, @RequestBody ReviewIssueRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    if (request.getDecision() == null || request.getDecision().isBlank()) {
      throw new IllegalArgumentException("decision must not be blank");
    }
    ReviewDecision decision = parseEnum(request.getDecision(), ReviewDecision.class, "decision");

    Issue issue = issueService.getIssue(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    long observedSpec =
        request.getObservedSpecRevision() != null && !request.getObservedSpecRevision().isBlank()
            ? ProjectDtoMapper.parseNonNegativeLong(
                request.getObservedSpecRevision(), "observedSpecRevision")
            : issue.getSpecRevision();

    long observedInput =
        request.getObservedInputSequence() != null && !request.getObservedInputSequence().isBlank()
            ? ProjectDtoMapper.parseNonNegativeLong(
                request.getObservedInputSequence(), "observedInputSequence")
            : issue.getInputSequence();

    IssueRun reviewerRun =
        issueRunService.reviewRun(
            issueId,
            null, // 人类 Review 时 runId 传 null
            IssueRunActorType.HUMAN,
            null, // 人类 Review 时 reviewerAgentName 传 null
            request.getTerminalActionId(),
            observedSpec,
            observedInput,
            decision,
            request.getSummary(),
            request.getVerification());

    return Results.ok(mapper.toSummaryDto(reviewerRun));
  }

  @PostMapping("/api/issues/{issueId}/cancel")
  public Result<IssueDTO> cancel(
      @PathVariable("issueId") String issueIdStr, @RequestBody CancelIssueRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue cancelled = issueService.cancelIssue(issueId, expectedVersion, request.getReason());
    return Results.ok(mapper.toDto(cancelled));
  }

  @PostMapping("/api/issues/{issueId}/retry")
  public ResponseEntity<Result<IssueInputDTO>> retry(
      @PathVariable("issueId") String issueIdStr, @RequestBody RetryIssueRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    IssueInput input = issueRunService.retryRun(issueId, request.getIdempotencyKey());
    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(input)));
  }

  @PostMapping("/api/issues/{issueId}/archive")
  public Result<IssueDTO> archive(
      @PathVariable("issueId") String issueIdStr, @RequestBody ArchiveIssueRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue archived = issueService.archiveIssue(issueId, expectedVersion);
    return Results.ok(mapper.toDto(archived));
  }

  @PostMapping("/api/issues/{issueId}/unarchive")
  public Result<IssueDTO> unarchive(
      @PathVariable("issueId") String issueIdStr, @RequestBody UnarchiveIssueRequestDTO request) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue unarchived = issueService.unarchiveIssue(issueId, expectedVersion);
    return Results.ok(mapper.toDto(unarchived));
  }

  private UUID resolveRunSessionId(UUID runId) {
    if (runId == null) {
      return null;
    }
    List<HarnessSessionSummaryDTO> sessions = harnessOwnerQueryService.listIssueRunSessions(runId);
    return sessions.isEmpty()
        ? null
        : ProjectDtoMapper.parseUuid(sessions.get(0).getSessionId(), "sessionId");
  }

  private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, String fieldName) {
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(fieldName + " is invalid");
    }
  }
}
