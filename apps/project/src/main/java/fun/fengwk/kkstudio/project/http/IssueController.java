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
import fun.fengwk.kkstudio.project.dto.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.project.dto.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.project.dto.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.IssueActivityDTO;
import fun.fengwk.kkstudio.project.dto.IssueActivityRequestKind;
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
import fun.fengwk.kkstudio.project.dto.UpdateIssueRequestDTO;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
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
@RequestMapping("/api/project")
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

  @PostMapping("/projects/{projectId}/issues")
  public ResponseEntity<IssueDTO> createIssue(
      @PathVariable("projectId") UUID projectId, @RequestBody CreateIssueRequestDTO request) {
    Issue created =
        issueEditingService.createIssue(projectId, request.getTitle(), request.getDescription());
    return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
  }

  @GetMapping("/issues/{issueId}")
  public IssueDetailDTO getIssue(
      @PathVariable("issueId") UUID issueId,
      @RequestParam(value = "afterSequence", required = false) Long afterSequence,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    if (limit < 1 || limit > MAX_ACTIVITY_LIMIT) {
      throw DomainException.validation(
          "limit", "limit must be within [1, " + MAX_ACTIVITY_LIMIT + "]");
    }
    return issueDetailAssembler.assemble(
        issueId, afterSequence == null ? 0L : afterSequence, limit);
  }

  @PutMapping("/issues/{issueId}")
  public IssueDTO updateIssue(
      @PathVariable("issueId") UUID issueId, @RequestBody UpdateIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue updated =
        issueEditingService.updateIssue(
            issueId, expectedVersion, request.getTitle(), request.getDescription());
    return mapper.toDto(updated);
  }

  @PostMapping("/issues/{issueId}/transition")
  public IssueDTO transition(
      @PathVariable("issueId") UUID issueId, @RequestBody TransitionIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue updated =
        issueLifecycleService.transition(
            issueId, expectedVersion, request.getRequestKey(), request.getToState());
    return mapper.toDto(updated);
  }

  @PostMapping("/issues/{issueId}/block")
  public IssueDTO block(
      @PathVariable("issueId") UUID issueId, @RequestBody BlockIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue blocked =
        issueLifecycleService.blockIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getReason());
    return mapper.toDto(blocked);
  }

  @PostMapping("/issues/{issueId}/recover")
  public IssueDTO recover(
      @PathVariable("issueId") UUID issueId, @RequestBody RecoverIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue recovered =
        issueLifecycleService.recoverIssue(issueId, expectedVersion, request.getRequestKey());
    return mapper.toDto(recovered);
  }

  @PostMapping("/issues/{issueId}/resume")
  public IssueDTO resume(
      @PathVariable("issueId") UUID issueId, @RequestBody ResumeIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue resumed =
        issueLifecycleService.resumeIssue(issueId, expectedVersion, request.getRequestKey());
    return mapper.toDto(resumed);
  }

  @PostMapping("/issues/{issueId}/stop")
  public IssueDTO stop(
      @PathVariable("issueId") UUID issueId, @RequestBody StopIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue stopped =
        issueLifecycleService.stopIssue(
            issueId, expectedVersion, request.getRequestKey(), request.getDetail());
    return mapper.toDto(stopped);
  }

  @DeleteMapping("/issues/{issueId}")
  public ResponseEntity<Void> deleteIssue(
      @PathVariable("issueId") UUID issueId,
      @RequestParam("expectedVersion") long expectedVersion) {
    issueLifecycleService.deleteIssue(issueId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/issues/{issueId}/reopen")
  public IssueDTO reopen(
      @PathVariable("issueId") UUID issueId, @RequestBody ReopenIssueRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    Issue reopened =
        issueLifecycleService.reopen(issueId, expectedVersion, request.getRequestKey());
    return mapper.toDto(reopened);
  }

  @PostMapping("/issues/{issueId}/reset-budget")
  public IssueStageBudgetDTO resetBudget(
      @PathVariable("issueId") UUID issueId, @RequestBody ResetStageBudgetRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    StageBudgetView view =
        issueStageBudgetService.resetStageBudget(
            issueId,
            expectedVersion,
            request.getRequestKey(),
            request.getState(),
            request.getMaxRuns());
    return mapper.toDto(view);
  }

  @PostMapping("/issues/{issueId}/activities")
  public ResponseEntity<IssueActivityDTO> appendActivity(
      @PathVariable("issueId") UUID issueId, @RequestBody AppendIssueActivityRequestDTO request) {
    long expectedVersion = request.getExpectedVersion();
    IssueActivityKind kind =
        switch (request.getKind() == null ? IssueActivityRequestKind.COMMENT : request.getKind()) {
          case COMMENT -> IssueActivityKind.COMMENT;
          case INSTRUCTION -> IssueActivityKind.INSTRUCTION;
        };
    IssueActivity activity =
        issueEditingService.appendActivity(
            issueId, expectedVersion, kind, request.getRequestKey(), request.getBody());
    return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(activity));
  }

  @GetMapping("/issues/{issueId}/evidence")
  public List<IssueEvidenceDTO> listEvidence(@PathVariable("issueId") UUID issueId) {
    return issueEvidenceService.listEvidence(issueId).stream().map(mapper::toDto).toList();
  }

  @PostMapping("/issues/{issueId}/evidence")
  public ResponseEntity<IssueEvidenceDTO> addEvidence(
      @PathVariable("issueId") UUID issueId, @RequestBody AddIssueEvidenceRequestDTO request) {
    IssueEvidence evidence =
        issueEvidenceService.publishHumanUpload(issueId, request.getUploadId());
    return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(evidence));
  }
}
