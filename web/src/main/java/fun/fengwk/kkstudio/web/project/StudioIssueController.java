package fun.fengwk.kkstudio.web.project;

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

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.service.IssueEditingService;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueLifecycleService;
import fun.fengwk.kkstudio.project.service.IssueStageBudgetService;
import fun.fengwk.kkstudio.project.service.StageBudgetView;
import fun.fengwk.kkstudio.share.project.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDetailDTO;
import fun.fengwk.kkstudio.share.project.IssueEvidenceDTO;
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
import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.util.List;
import java.util.Locale;
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

  private final IssueEditingService issueEditingService;
  private final IssueLifecycleService issueLifecycleService;
  private final IssueStageBudgetService issueStageBudgetService;
  private final IssueEvidenceService issueEvidenceService;
  private final ProjectDtoMapper mapper;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueDetailAssembler issueDetailAssembler;

  @Autowired
  public StudioIssueController(
      IssueEditingService issueEditingService,
      IssueLifecycleService issueLifecycleService,
      IssueStageBudgetService issueStageBudgetService,
      IssueEvidenceService issueEvidenceService,
      ProjectDtoMapper mapper,
      IssueActivityRepository issueActivityRepository,
      IssueDetailAssembler issueDetailAssembler) {
    this.issueEditingService = Objects.requireNonNull(issueEditingService, "issueEditingService");
    this.issueLifecycleService =
        Objects.requireNonNull(issueLifecycleService, "issueLifecycleService");
    this.issueStageBudgetService =
        Objects.requireNonNull(issueStageBudgetService, "issueStageBudgetService");
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.issueActivityRepository = issueActivityRepository;
    this.issueDetailAssembler =
        Objects.requireNonNull(issueDetailAssembler, "issueDetailAssembler");
  }

  @PostMapping("/api/projects/{projectId}/issues")
  public ResponseEntity<ApiResponse<IssueDTO>> createIssue(
      @PathVariable("projectId") String projectIdStr, @RequestBody CreateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = Uuids.parseCanonical(projectIdStr, "projectId");
    Issue created =
        issueEditingService.createIssue(projectId, request.getTitle(), request.getDescription());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(mapper.toDto(created)));
  }

  @GetMapping("/api/issues/{issueId}")
  public ApiResponse<IssueDetailDTO> getIssue(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam(value = "afterSequence", required = false, defaultValue = "0")
          String afterSequenceStr,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long afterSequence = parseActivityCursor(afterSequenceStr);
    int activityLimit = parseActivityLimit(limit);
    return ApiResponse.ok(issueDetailAssembler.assemble(issueId, afterSequence, activityLimit));
  }

  @GetMapping("/api/issues/{issueId}/activities")
  public ApiResponse<List<IssueActivityDTO>> listActivities(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam(value = "afterSequence", required = false, defaultValue = "0")
          String afterSequenceStr,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long afterSequence = parseActivityCursor(afterSequenceStr);
    int activityLimit = parseActivityLimit(limit);
    issueEditingService.getIssue(issueId);
    List<IssueActivity> activities =
        issueEditingService.listActivities(issueId, afterSequence, activityLimit);
    return ApiResponse.ok(activities.stream().map(mapper::toDto).toList());
  }

  @PutMapping("/api/issues/{issueId}")
  public ApiResponse<IssueDTO> updateIssue(
      @PathVariable("issueId") String issueIdStr, @RequestBody UpdateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueEditingService.updateIssue(
            issueId, expectedVersion, request.getTitle(), request.getDescription());
    return ApiResponse.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/transition")
  public ApiResponse<IssueDTO> transition(
      @PathVariable("issueId") String issueIdStr, @RequestBody TransitionIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueLifecycleService.transition(
            issueId, expectedVersion, request.getRequestKey(), request.getToState());
    return ApiResponse.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/block")
  public ApiResponse<IssueDTO> block(
      @PathVariable("issueId") String issueIdStr, @RequestBody BlockIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue blocked =
        issueLifecycleService.blockIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getReason());
    return ApiResponse.ok(mapper.toDto(blocked));
  }

  @PostMapping("/api/issues/{issueId}/recover")
  public ApiResponse<IssueDTO> recover(
      @PathVariable("issueId") String issueIdStr, @RequestBody RecoverIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue recovered =
        issueLifecycleService.recoverIssue(issueId, expectedVersion, request.getRequestKey());
    return ApiResponse.ok(mapper.toDto(recovered));
  }

  @PostMapping("/api/issues/{issueId}/pause")
  public ApiResponse<IssueDTO> pause(
      @PathVariable("issueId") String issueIdStr, @RequestBody PauseIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    PauseReason reason = parseEnum(request.getReason(), PauseReason.class, "reason");
    Issue paused =
        issueLifecycleService.pauseIssue(
            issueId, expectedVersion, request.getRequestKey(), reason, request.getDetail());
    return ApiResponse.ok(mapper.toDto(paused));
  }

  @PostMapping("/api/issues/{issueId}/resume")
  public ApiResponse<IssueDTO> resume(
      @PathVariable("issueId") String issueIdStr, @RequestBody ResumeIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue resumed =
        issueLifecycleService.resumeIssue(issueId, expectedVersion, request.getRequestKey());
    return ApiResponse.ok(mapper.toDto(resumed));
  }

  @PostMapping("/api/issues/{issueId}/stop")
  public ApiResponse<IssueDTO> stop(
      @PathVariable("issueId") String issueIdStr, @RequestBody StopIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue stopped =
        issueLifecycleService.stopIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getDetail());
    return ApiResponse.ok(mapper.toDto(stopped));
  }

  @PostMapping("/api/issues/{issueId}/resolve-unknown")
  public ApiResponse<IssueDTO> resolveUnknown(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody ResolveUnknownIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue resolved =
        issueLifecycleService.resolveUnknown(
            issueId, expectedVersion, request.getRequestKey(), request.getVerification());
    return ApiResponse.ok(mapper.toDto(resolved));
  }

  @DeleteMapping("/api/issues/{issueId}")
  public ResponseEntity<Void> deleteIssue(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam("expectedVersion") String expectedVersionStr) {
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion = DecimalLongs.parseNonNegative(expectedVersionStr, "expectedVersion");
    issueLifecycleService.deleteIssue(issueId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/issues/{issueId}/reopen")
  public ApiResponse<IssueDTO> reopen(
      @PathVariable("issueId") String issueIdStr, @RequestBody ReopenIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue reopened =
        issueLifecycleService.reopen(issueId, expectedVersion, request.getRequestKey());
    return ApiResponse.ok(mapper.toDto(reopened));
  }

  @PostMapping("/api/issues/{issueId}/budget-reset")
  public ApiResponse<IssueStageBudgetDTO> resetBudget(
      @PathVariable("issueId") String issueIdStr, @RequestBody ResetStageBudgetRequestDTO request) {
    Objects.requireNonNull(request, "request");
    if (request.getMaxRuns() == null) {
      throw new IllegalArgumentException("maxRuns must not be null");
    }
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    StageBudgetView view =
        issueStageBudgetService.resetStageBudget(
            issueId,
            expectedVersion,
            request.getRequestKey(),
            request.getState(),
            request.getMaxRuns());
    return ApiResponse.ok(mapper.toDto(view));
  }

  @PostMapping("/api/issues/{issueId}/archive")
  public ApiResponse<IssueDTO> archive(
      @PathVariable("issueId") String issueIdStr, @RequestBody ArchiveIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue archived = issueLifecycleService.archiveIssue(issueId, expectedVersion);
    return ApiResponse.ok(mapper.toDto(archived));
  }

  @PostMapping("/api/issues/{issueId}/unarchive")
  public ApiResponse<IssueDTO> unarchive(
      @PathVariable("issueId") String issueIdStr, @RequestBody UnarchiveIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue unarchived = issueLifecycleService.unarchiveIssue(issueId, expectedVersion);
    return ApiResponse.ok(mapper.toDto(unarchived));
  }

  @PostMapping("/api/issues/{issueId}/activities")
  public ResponseEntity<ApiResponse<IssueActivityDTO>> appendActivity(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody AppendIssueActivityRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    if (request.getBody() == null || request.getBody().isBlank()) {
      throw new IllegalArgumentException("body must not be blank");
    }
    String kind = request.getKind();
    if ("INSTRUCTION".equalsIgnoreCase(kind)) {
      issueEditingService.appendInstruction(
          issueId, expectedVersion, request.getRequestKey(), request.getBody());
    } else if (kind == null || kind.isBlank() || "COMMENT".equalsIgnoreCase(kind)) {
      issueEditingService.appendComment(
          issueId, expectedVersion, request.getRequestKey(), request.getBody());
    } else {
      throw new IllegalArgumentException("kind is invalid");
    }

    String activityKey =
        (kind != null && "INSTRUCTION".equalsIgnoreCase(kind) ? "instruction:" : "comment:")
            + request.getRequestKey();
    IssueActivity activity = null;
    if (issueActivityRepository != null) {
      activity = issueActivityRepository.findByIdempotencyKey(issueId, activityKey);
    }
    if (activity == null) {
      List<IssueActivity> recent =
          issueEditingService.listActivities(issueId, 0, MAX_ACTIVITY_LIMIT);
      for (IssueActivity a : recent) {
        if (Objects.equals(activityKey, a.getIdempotencyKey())) {
          activity = a;
          break;
        }
      }
    }
    if (activity == null) {
      throw new IllegalStateException("appended activity could not be found");
    }

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(mapper.toDto(activity)));
  }

  @GetMapping("/api/issues/{issueId}/evidence")
  public ApiResponse<List<IssueEvidenceDTO>> listEvidence(
      @PathVariable("issueId") String issueIdStr) {
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    issueEditingService.getIssue(issueId);
    return ApiResponse.ok(
        issueEvidenceService.listEvidence(issueId).stream().map(mapper::toDto).toList());
  }

  @PostMapping("/api/issues/{issueId}/evidence")
  public ResponseEntity<ApiResponse<IssueEvidenceDTO>> addEvidence(
      @PathVariable("issueId") String issueIdStr, @RequestBody AddIssueEvidenceRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = Uuids.parseCanonical(issueIdStr, "issueId");
    UUID uploadId = Uuids.parseCanonical(request.getUploadId(), "uploadId");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(issueId, uploadId);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(mapper.toDto(evidence)));
  }

  private static long parseActivityCursor(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    return DecimalLongs.parseNonNegative(value, "afterSequence");
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
