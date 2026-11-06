package fun.fengwk.kkstudio.project.http;

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

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.project.dto.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.project.dto.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.project.dto.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.IssueActivityDTO;
import fun.fengwk.kkstudio.project.dto.IssueDTO;
import fun.fengwk.kkstudio.project.dto.IssueDetailDTO;
import fun.fengwk.kkstudio.project.dto.IssueEvidenceDTO;
import fun.fengwk.kkstudio.project.dto.IssueStageBudgetDTO;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.dto.RecoverIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.ReopenIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.ResetStageBudgetRequestDTO;
import fun.fengwk.kkstudio.project.dto.ResumeIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.StopIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.TransitionIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateIssueRequestDTO;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.service.IssueDetailAssembler;
import fun.fengwk.kkstudio.project.service.IssueEditingService;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueLifecycleService;
import fun.fengwk.kkstudio.project.service.IssueStageBudgetService;
import fun.fengwk.kkstudio.project.service.StageBudgetView;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 领域 REST 控制器。
 *
 * <p>提供 Issue 需求事实、工作流流转、阻塞/恢复、人工终止/显式恢复、阶段额度重置、活动事实流与证据公开的权威 HTTP 操作面。
 */
@RestController
@RequestMapping
public class IssueController {

  private static final int DEFAULT_ACTIVITY_LIMIT = 50;
  private static final int MAX_ACTIVITY_LIMIT = 200;

  private final IssueEditingService issueEditingService;
  private final IssueLifecycleService issueLifecycleService;
  private final IssueStageBudgetService issueStageBudgetService;
  private final IssueEvidenceService issueEvidenceService;
  private final ProjectDtoMapper mapper;
  private final IssueDetailAssembler issueDetailAssembler;

  @Autowired
  public IssueController(
      IssueEditingService issueEditingService,
      IssueLifecycleService issueLifecycleService,
      IssueStageBudgetService issueStageBudgetService,
      IssueEvidenceService issueEvidenceService,
      ProjectDtoMapper mapper,
      IssueDetailAssembler issueDetailAssembler) {
    this.issueEditingService = Objects.requireNonNull(issueEditingService, "issueEditingService");
    this.issueLifecycleService =
        Objects.requireNonNull(issueLifecycleService, "issueLifecycleService");
    this.issueStageBudgetService =
        Objects.requireNonNull(issueStageBudgetService, "issueStageBudgetService");
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.issueDetailAssembler =
        Objects.requireNonNull(issueDetailAssembler, "issueDetailAssembler");
  }

  @PostMapping("/api/projects/{projectId}/issues")
  public ResponseEntity<ApiResponse<IssueDTO>> createIssue(
      @PathVariable("projectId") String projectIdStr, @RequestBody CreateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = ProjectHttpInputs.uuid(projectIdStr, "projectId");
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
      @RequestParam(value = "limit", required = false, defaultValue = "50") String limitStr) {
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long afterSequence = parseActivityCursor(afterSequenceStr);
    int activityLimit =
        ProjectHttpInputs.limit(limitStr, DEFAULT_ACTIVITY_LIMIT, MAX_ACTIVITY_LIMIT);
    return ApiResponse.ok(issueDetailAssembler.assemble(issueId, afterSequence, activityLimit));
  }

  @PutMapping("/api/issues/{issueId}")
  public ApiResponse<IssueDTO> updateIssue(
      @PathVariable("issueId") String issueIdStr, @RequestBody UpdateIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueEditingService.updateIssue(
            issueId, expectedVersion, request.getTitle(), request.getDescription());
    return ApiResponse.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/transition")
  public ApiResponse<IssueDTO> transition(
      @PathVariable("issueId") String issueIdStr, @RequestBody TransitionIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue updated =
        issueLifecycleService.transition(
            issueId, expectedVersion, request.getRequestKey(), request.getToState());
    return ApiResponse.ok(mapper.toDto(updated));
  }

  @PostMapping("/api/issues/{issueId}/block")
  public ApiResponse<IssueDTO> block(
      @PathVariable("issueId") String issueIdStr, @RequestBody BlockIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue blocked =
        issueLifecycleService.blockIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getReason());
    return ApiResponse.ok(mapper.toDto(blocked));
  }

  @PostMapping("/api/issues/{issueId}/recover")
  public ApiResponse<IssueDTO> recover(
      @PathVariable("issueId") String issueIdStr, @RequestBody RecoverIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue recovered =
        issueLifecycleService.recoverIssue(issueId, expectedVersion, request.getRequestKey());
    return ApiResponse.ok(mapper.toDto(recovered));
  }

  @PostMapping("/api/issues/{issueId}/resume")
  public ApiResponse<IssueDTO> resume(
      @PathVariable("issueId") String issueIdStr, @RequestBody ResumeIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue resumed =
        issueLifecycleService.resumeIssue(issueId, expectedVersion, request.getRequestKey());
    return ApiResponse.ok(mapper.toDto(resumed));
  }

  @PostMapping("/api/issues/{issueId}/stop")
  public ApiResponse<IssueDTO> stop(
      @PathVariable("issueId") String issueIdStr, @RequestBody StopIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue stopped =
        issueLifecycleService.stopIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getDetail());
    return ApiResponse.ok(mapper.toDto(stopped));
  }

  @DeleteMapping("/api/issues/{issueId}")
  public ResponseEntity<Void> deleteIssue(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam("expectedVersion") String expectedVersionStr) {
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion = ProjectHttpInputs.nonNegative(expectedVersionStr, "expectedVersion");
    issueLifecycleService.deleteIssue(issueId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/issues/{issueId}/reopen")
  public ApiResponse<IssueDTO> reopen(
      @PathVariable("issueId") String issueIdStr, @RequestBody ReopenIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue reopened =
        issueLifecycleService.reopen(issueId, expectedVersion, request.getRequestKey());
    return ApiResponse.ok(mapper.toDto(reopened));
  }

  @PostMapping("/api/issues/{issueId}/budget-reset")
  public ApiResponse<IssueStageBudgetDTO> resetBudget(
      @PathVariable("issueId") String issueIdStr, @RequestBody ResetStageBudgetRequestDTO request) {
    Objects.requireNonNull(request, "request");
    if (request.getMaxRuns() == null) {
      throw DomainException.validation("maxRuns", "maxRuns must not be null");
    }
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
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
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue archived = issueLifecycleService.archiveIssue(issueId, expectedVersion);
    return ApiResponse.ok(mapper.toDto(archived));
  }

  @PostMapping("/api/issues/{issueId}/unarchive")
  public ApiResponse<IssueDTO> unarchive(
      @PathVariable("issueId") String issueIdStr, @RequestBody UnarchiveIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    Issue unarchived = issueLifecycleService.unarchiveIssue(issueId, expectedVersion);
    return ApiResponse.ok(mapper.toDto(unarchived));
  }

  @PostMapping("/api/issues/{issueId}/activities")
  public ResponseEntity<ApiResponse<IssueActivityDTO>> appendActivity(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody AppendIssueActivityRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectHttpInputs.nonNegative(request.getExpectedVersion(), "expectedVersion");
    if (request.getBody() == null || request.getBody().isBlank()) {
      throw DomainException.validation("body", "body must not be blank");
    }
    IssueActivity activity =
        issueEditingService.appendActivity(
            issueId,
            expectedVersion,
            request.getKind(),
            request.getRequestKey(),
            request.getBody());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(mapper.toDto(activity)));
  }

  @GetMapping("/api/issues/{issueId}/evidence")
  public ApiResponse<List<IssueEvidenceDTO>> listEvidence(
      @PathVariable("issueId") String issueIdStr) {
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    return ApiResponse.ok(
        issueEvidenceService.listEvidence(issueId).stream().map(mapper::toDto).toList());
  }

  @PostMapping("/api/issues/{issueId}/evidence")
  public ResponseEntity<ApiResponse<IssueEvidenceDTO>> addEvidence(
      @PathVariable("issueId") String issueIdStr, @RequestBody AddIssueEvidenceRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectHttpInputs.uuid(issueIdStr, "issueId");
    UUID uploadId = ProjectHttpInputs.uuid(request.getUploadId(), "uploadId");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(issueId, uploadId);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(mapper.toDto(evidence)));
  }

  private static long parseActivityCursor(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    return ProjectHttpInputs.nonNegative(value, "afterSequence");
  }
}
