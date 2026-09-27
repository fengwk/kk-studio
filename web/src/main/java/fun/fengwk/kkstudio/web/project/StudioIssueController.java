package fun.fengwk.kkstudio.web.project;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.beans.factory.annotation.Autowired;
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

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.IssueService.StageBudgetView;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentThreadDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDetailDTO;
import fun.fengwk.kkstudio.share.project.IssueEvidenceDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueStageBudgetDTO;
import fun.fengwk.kkstudio.share.project.PauseIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.RecoverIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ReopenIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ResetStageBudgetRequestDTO;
import fun.fengwk.kkstudio.share.project.ResolveUnknownIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ResumeIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.StopIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.TransitionIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 领域 REST 控制器。
 *
 * <p>提供 Issue 需求事实、工作流流转、阻塞/恢复、控制暂停/继续、阶段额度重置、活动事实流与证据公开的权威 HTTP 操作面。
 */
@RestController
@RequestMapping
public class StudioIssueController {

  private static final int DEFAULT_ACTIVITY_LIMIT = 50;
  private static final int MAX_ACTIVITY_LIMIT = 200;

  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final IssueEvidenceService issueEvidenceService;
  private final ProjectService projectService;
  private final ProjectDtoMapper mapper;
  private final IssueActivityRepository issueActivityRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  @Autowired
  public StudioIssueController(
      IssueService issueService,
      IssueRunService issueRunService,
      IssueEvidenceService issueEvidenceService,
      ProjectService projectService,
      ProjectDtoMapper mapper,
      IssueActivityRepository issueActivityRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
    this.projectService = projectService;
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.issueActivityRepository = issueActivityRepository;
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  public StudioIssueController(
      IssueService issueService,
      IssueRunService issueRunService,
      IssueEvidenceService issueEvidenceService,
      ProjectService projectService,
      ProjectDtoMapper mapper,
      IssueActivityRepository issueActivityRepository) {
    this(
        issueService,
        issueRunService,
        issueEvidenceService,
        projectService,
        mapper,
        issueActivityRepository,
        new ProjectWorkflowJsonCodec());
  }

  public StudioIssueController(
      IssueService issueService,
      IssueRunService issueRunService,
      IssueEvidenceService issueEvidenceService,
      ProjectDtoMapper mapper) {
    this(
        issueService,
        issueRunService,
        issueEvidenceService,
        null,
        mapper,
        null,
        new ProjectWorkflowJsonCodec());
  }

  @PostMapping("/api/projects/{projectId}/issues")
  public ResponseEntity<Result<IssueDTO>> createIssue(
      @PathVariable("projectId") String projectIdStr, @RequestBody CreateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    Issue created =
        issueService.createIssue(projectId, request.getTitle(), request.getDescription());
    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(created)));
  }

  @GetMapping("/api/issues/{issueId}")
  public Result<IssueDetailDTO> getIssue(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam(value = "afterSequence", required = false, defaultValue = "0")
          String afterSequenceStr,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long afterSequence = parseActivityCursor(afterSequenceStr);
    int activityLimit = parseActivityLimit(limit);

    Issue issue = issueService.getIssue(issueId);
    List<IssueActivity> activities =
        issueService.listActivities(issueId, afterSequence, activityLimit);
    List<IssueActivityDTO> activityDtos = activities.stream().map(mapper::toDto).toList();

    String nextActivityCursor = null;
    if (activities.size() == activityLimit) {
      long lastSequence = activities.get(activities.size() - 1).getSequence();
      nextActivityCursor = String.valueOf(lastSequence);
    }

    List<IssueAgentThread> agentThreads = issueService.listAgentThreads(issueId);
    List<IssueAgentThreadDTO> agentThreadDtos = agentThreads.stream().map(mapper::toDto).toList();
    Map<UUID, String> threadToAgent = new HashMap<>();
    for (IssueAgentThread thread : agentThreads) {
      threadToAgent.put(thread.threadId(), thread.agentName());
    }

    List<IssueRun> runs = issueRunService.listRuns(issueId);
    List<IssueRunDTO> runDtos = new ArrayList<>(runs.size());
    for (IssueRun run : runs) {
      runDtos.add(mapper.toDto(run, threadToAgent.get(run.getThreadId())));
    }

    IssueRun activeRun =
        runs.stream()
            .filter(
                run ->
                    run.getStatus() == IssueRunStatus.RUNNING
                        || run.getStatus() == IssueRunStatus.WAITING)
            .findFirst()
            .orElse(null);
    IssueRunDTO currentRunDto =
        activeRun != null
            ? mapper.toDto(activeRun, threadToAgent.get(activeRun.getThreadId()))
            : null;

    IssueRun latestRun =
        runs.stream().max(Comparator.comparingLong(IssueRun::getOrdinal)).orElse(null);
    IssueRunDTO latestRunDto =
        latestRun != null
            ? mapper.toDto(latestRun, threadToAgent.get(latestRun.getThreadId()))
            : null;

    List<IssueStageBudgetDTO> stageBudgets = new ArrayList<>();
    if (projectService != null) {
      try {
        Project project = projectService.getProject(issue.getProjectId());
        if (project != null
            && project.getWorkflowJson() != null
            && !project.getWorkflowJson().isBlank()) {
          ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
          for (ProjectWorkflowState state : workflow.workStages()) {
            try {
              StageBudgetView view = issueService.getStageBudget(issueId, state.state().value());
              stageBudgets.add(mapper.toDto(view));
            } catch (ProjectNotFoundException ignored) {
              // 阶段额度尚未授权时静默跳过
            }
          }
        }
      } catch (ProjectNotFoundException ignored) {
        // 项目未找到时跳过
      }
    }

    IssueDetailDTO detail =
        IssueDetailDTO.builder()
            .issue(mapper.toDto(issue))
            .activities(activityDtos)
            .nextActivityCursor(nextActivityCursor)
            .runs(runDtos)
            .currentRun(currentRunDto)
            .latestRun(latestRunDto)
            .stageBudgets(stageBudgets)
            .agentThreads(agentThreadDtos)
            .build();

    return Results.ok(detail);
  }

  @GetMapping("/api/issues/{issueId}/activities")
  public Result<List<IssueActivityDTO>> listActivities(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam(value = "afterSequence", required = false, defaultValue = "0")
          String afterSequenceStr,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long afterSequence = parseActivityCursor(afterSequenceStr);
    int activityLimit = parseActivityLimit(limit);
    issueService.getIssue(issueId);
    List<IssueActivity> activities =
        issueService.listActivities(issueId, afterSequence, activityLimit);
    return Results.ok(activities.stream().map(mapper::toDto).toList());
  }

  @PutMapping("/api/issues/{issueId}")
  public Result<IssueDTO> updateIssue(
      @PathVariable("issueId") String issueIdStr, @RequestBody UpdateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueService.updateIssue(
            issueId, expectedVersion, request.getTitle(), request.getDescription());
    return Results.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/transition")
  public Result<IssueDTO> transition(
      @PathVariable("issueId") String issueIdStr, @RequestBody TransitionIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueService.transition(
            issueId, expectedVersion, request.getRequestKey(), request.getToState());
    return Results.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/block")
  public Result<IssueDTO> block(
      @PathVariable("issueId") String issueIdStr, @RequestBody BlockIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue blocked =
        issueService.blockIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getReason());
    return Results.ok(mapper.toDto(blocked));
  }

  @PostMapping("/api/issues/{issueId}/recover")
  public Result<IssueDTO> recover(
      @PathVariable("issueId") String issueIdStr, @RequestBody RecoverIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue recovered = issueService.recoverIssue(issueId, expectedVersion, request.getRequestKey());
    return Results.ok(mapper.toDto(recovered));
  }

  @PostMapping("/api/issues/{issueId}/pause")
  public Result<IssueDTO> pause(
      @PathVariable("issueId") String issueIdStr, @RequestBody PauseIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    PauseReason reason = parseEnum(request.getReason(), PauseReason.class, "reason");
    Issue paused =
        issueService.pauseIssue(
            issueId, expectedVersion, request.getRequestKey(), reason, request.getDetail());
    return Results.ok(mapper.toDto(paused));
  }

  @PostMapping("/api/issues/{issueId}/resume")
  public Result<IssueDTO> resume(
      @PathVariable("issueId") String issueIdStr, @RequestBody ResumeIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue resumed = issueService.resumeIssue(issueId, expectedVersion, request.getRequestKey());
    return Results.ok(mapper.toDto(resumed));
  }

  @PostMapping("/api/issues/{issueId}/stop")
  public Result<IssueDTO> stop(
      @PathVariable("issueId") String issueIdStr, @RequestBody StopIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue stopped =
        issueService.stopIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getDetail());
    return Results.ok(mapper.toDto(stopped));
  }

  @PostMapping("/api/issues/{issueId}/resolve-unknown")
  public Result<IssueDTO> resolveUnknown(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody ResolveUnknownIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue resolved =
        issueService.resolveUnknown(
            issueId, expectedVersion, request.getRequestKey(), request.getVerification());
    return Results.ok(mapper.toDto(resolved));
  }

  @DeleteMapping("/api/issues/{issueId}")
  public ResponseEntity<Void> deleteIssue(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam("expectedVersion") String expectedVersionStr) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(expectedVersionStr, "expectedVersion");
    issueService.deleteIssue(issueId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/issues/{issueId}/reopen")
  public Result<IssueDTO> reopen(
      @PathVariable("issueId") String issueIdStr, @RequestBody ReopenIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue reopened = issueService.reopen(issueId, expectedVersion, request.getRequestKey());
    return Results.ok(mapper.toDto(reopened));
  }

  @PostMapping("/api/issues/{issueId}/budget-reset")
  public Result<IssueStageBudgetDTO> resetBudget(
      @PathVariable("issueId") String issueIdStr, @RequestBody ResetStageBudgetRequestDTO request) {
    Objects.requireNonNull(request, "request");
    if (request.getMaxRuns() == null) {
      throw new IllegalArgumentException("maxRuns must not be null");
    }
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    StageBudgetView view =
        issueService.resetStageBudget(
            issueId,
            expectedVersion,
            request.getRequestKey(),
            request.getState(),
            request.getMaxRuns());
    return Results.ok(mapper.toDto(view));
  }

  @PostMapping("/api/issues/{issueId}/archive")
  public Result<IssueDTO> archive(
      @PathVariable("issueId") String issueIdStr, @RequestBody ArchiveIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue archived = issueService.archiveIssue(issueId, expectedVersion);
    return Results.ok(mapper.toDto(archived));
  }

  @PostMapping("/api/issues/{issueId}/unarchive")
  public Result<IssueDTO> unarchive(
      @PathVariable("issueId") String issueIdStr, @RequestBody UnarchiveIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue unarchived = issueService.unarchiveIssue(issueId, expectedVersion);
    return Results.ok(mapper.toDto(unarchived));
  }

  @PostMapping("/api/issues/{issueId}/activities")
  public ResponseEntity<Result<IssueActivityDTO>> appendActivity(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody AppendIssueActivityRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    if (request.getBody() == null || request.getBody().isBlank()) {
      throw new IllegalArgumentException("body must not be blank");
    }
    String kind = request.getKind();
    if ("INSTRUCTION".equalsIgnoreCase(kind)) {
      issueService.appendInstruction(
          issueId, expectedVersion, request.getRequestKey(), request.getBody());
    } else if (kind == null || kind.isBlank() || "COMMENT".equalsIgnoreCase(kind)) {
      issueService.appendComment(
          issueId, expectedVersion, request.getRequestKey(), request.getBody());
    } else {
      throw new IllegalArgumentException("kind is invalid");
    }

    IssueActivity activity = null;
    if (issueActivityRepository != null) {
      activity = issueActivityRepository.findByIdempotencyKey(issueId, request.getRequestKey());
    }
    if (activity == null) {
      List<IssueActivity> recent = issueService.listActivities(issueId, 0, MAX_ACTIVITY_LIMIT);
      for (IssueActivity a : recent) {
        if (Objects.equals(request.getRequestKey(), a.getIdempotencyKey())) {
          activity = a;
          break;
        }
      }
    }
    if (activity == null) {
      throw new IllegalStateException("appended activity could not be found");
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(activity)));
  }

  @PostMapping("/api/issues/{issueId}/evidence")
  public ResponseEntity<Result<IssueEvidenceDTO>> addEvidence(
      @PathVariable("issueId") String issueIdStr, @RequestBody AddIssueEvidenceRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    UUID uploadId = ProjectDtoMapper.parseUuid(request.getUploadId(), "uploadId");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(issueId, uploadId);

    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(evidence)));
  }

  private static long parseActivityCursor(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    return ProjectDtoMapper.parseNonNegativeLong(value, "afterSequence");
  }

  private static int parseActivityLimit(int limit) {
    if (limit < 1 || limit > MAX_ACTIVITY_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_ACTIVITY_LIMIT);
    }
    return limit;
  }

  private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(fieldName + " is invalid");
    }
  }
}
