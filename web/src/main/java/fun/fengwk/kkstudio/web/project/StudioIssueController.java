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

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.share.project.AddIssueDependencyRequestDTO;
import fun.fengwk.kkstudio.share.project.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CancelIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ChangeIssueStatusRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentSessionDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDependencyDTO;
import fun.fengwk.kkstudio.share.project.IssueDetailDTO;
import fun.fengwk.kkstudio.share.project.IssueEvidenceDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.RecoverIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.RetryIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ReviewIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Issue 领域 REST 控制器。
 *
 * <p>人工与 Agent 走同一状态机：人可显式设状态、阻塞/恢复、重试、评审、以及追加带目标职责的定向输入。 Issue 当前要求就是 {@code description}
 * 正文，Activity 是唯一有序事实流，因此读取面用 {@code afterSequence}/{@code limit} 分页而不是全量展开。
 */
@AllArgsConstructor
@RestController
@RequestMapping
public class StudioIssueController {

  /** Issue 详情默认返回的 Activity 窗口大小。 */
  private static final int DEFAULT_ACTIVITY_LIMIT = 50;

  /** Agent 读取 Activity 的上限，与 issue_read 工具一致。 */
  private static final int MAX_ACTIVITY_LIMIT = 200;

  /** 人可写入的 Activity 种类：系统与 Agent 事实（RECOVERY/RETRY/SPEC_CHANGE/REVIEW_DECISION）只能由对应流程产生。 */
  private static final Set<IssueActivityKind> HUMAN_WRITABLE_KINDS =
      Set.of(
          IssueActivityKind.COMMENT, IssueActivityKind.INSTRUCTION, IssueActivityKind.HUMAN_INPUT);

  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final IssueEvidenceService issueEvidenceService;
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
  public Result<IssueDetailDTO> getIssue(
      @PathVariable("issueId") String issueIdStr,
      @RequestParam(value = "afterSequence", required = false, defaultValue = "0")
          String afterSequenceStr,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long afterSequence = parseActivityCursor(afterSequenceStr);
    int activityLimit = parseActivityLimit(limit);

    Issue issue = issueService.getIssue(issueId);
    boolean blocked = issueService.isBlocked(issueId);
    List<IssueDependency> deps = issueService.listDependencies(issueId);
    List<IssueActivity> activities =
        issueService.listActivitiesPage(issueId, afterSequence, activityLimit);
    List<IssueRun> runs = issueRunService.listRuns(issueId);

    Map<String, IssueAgentSession> agentSessions = new HashMap<>();
    List<IssueRunDTO> runDtos = new ArrayList<>(runs.size());
    for (IssueRun run : runs) {
      runDtos.add(mapper.toDetailDto(run, resolveAgentSession(agentSessions, run)));
    }

    IssueRun activeRun = issueRunService.getActiveRun(issueId);
    IssueRun latestRun = issueRunService.getLatestRun(issueId);

    IssueDetailDTO detail =
        IssueDetailDTO.builder()
            .issue(mapper.toDto(issue))
            .blocked(blocked)
            .dependencies(deps.stream().map(mapper::toDto).toList())
            .sessions(listAgentSessions(issue))
            .activities(activities.stream().map(mapper::toDto).toList())
            .evidence(
                issueEvidenceService.listEvidence(issueId).stream().map(mapper::toDto).toList())
            .nextActivityCursor(nextActivityCursor(issueId, activities, activityLimit))
            .runs(runDtos)
            .currentRun(
                activeRun == null
                    ? null
                    : mapper.toDetailDto(activeRun, resolveAgentSession(agentSessions, activeRun)))
            .latestRun(
                latestRun == null
                    ? null
                    : mapper.toDetailDto(latestRun, resolveAgentSession(agentSessions, latestRun)))
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
    return Results.ok(
        issueService.listActivitiesPage(issueId, afterSequence, activityLimit).stream()
            .map(mapper::toDto)
            .toList());
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
    Objects.requireNonNull(request, "request");
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

  @PostMapping("/api/issues/{issueId}/block")
  public Result<IssueDTO> block(
      @PathVariable("issueId") String issueIdStr, @RequestBody BlockIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue blocked = issueService.blockIssue(issueId, expectedVersion, request.getReason());
    return Results.ok(mapper.toDto(blocked));
  }

  @PostMapping("/api/issues/{issueId}/recover")
  public Result<IssueDTO> recover(
      @PathVariable("issueId") String issueIdStr, @RequestBody RecoverIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue recovered =
        issueService.recoverIssue(
            issueId,
            expectedVersion,
            Boolean.TRUE.equals(request.getToBacklog()),
            request.getComment());
    return Results.ok(mapper.toDto(recovered));
  }

  @PostMapping("/api/issues/{issueId}/dependencies")
  public ResponseEntity<Result<IssueDependencyDTO>> addDependency(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody AddIssueDependencyRequestDTO request) {
    Objects.requireNonNull(request, "request");
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

  /**
   * 追加人为 Activity：无 {@code targetRole} 的评论只留痕，设置 {@code targetRole} 即定向补充输入给已有参与者。
   *
   * <p>{@code kind} 省略时按是否定向推导：定向为 {@code INSTRUCTION}，否则为 {@code COMMENT}。权限来自 Agent
   * 身份与目标职责，不由正文内容推导。
   */
  @PostMapping("/api/issues/{issueId}/activities")
  public ResponseEntity<Result<IssueActivityDTO>> appendActivity(
      @PathVariable("issueId") String issueIdStr,
      @RequestBody AppendIssueActivityRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    if (request.getBody() == null || request.getBody().isBlank()) {
      throw new IllegalArgumentException("body must not be blank");
    }
    IssueRunRole targetRole =
        request.getTargetRole() != null && !request.getTargetRole().isBlank()
            ? parseEnum(request.getTargetRole(), IssueRunRole.class, "targetRole")
            : null;
    IssueActivityKind kind = resolveHumanActivityKind(request.getKind(), targetRole);

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(kind)
            .actorType(IssueActivityActorType.HUMAN)
            .targetRole(targetRole)
            .body(request.getBody().trim())
            .idempotencyKey(request.getIdempotencyKey())
            .build();

    IssueActivity appended = issueService.appendActivity(activity);

    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(appended)));
  }

  /**
   * 人工上传转为 Issue 公开证据：请求只携带已 READY 的 {@code uploadId}，服务端在单个事务内 {@code lockReady -> retain Issue 引用
   * -> delete upload} 原子转移引用，不复制字节，也不信任客户端声明的文件名。
   */
  @PostMapping("/api/issues/{issueId}/evidence")
  public ResponseEntity<Result<IssueEvidenceDTO>> addEvidence(
      @PathVariable("issueId") String issueIdStr, @RequestBody AddIssueEvidenceRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    UUID uploadId = ProjectDtoMapper.parseUuid(request.getUploadId(), "uploadId");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(issueId, uploadId);

    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(evidence)));
  }

  /** 人工评审：被审查提交由服务端按当前 {@code IN_REVIEW} 的合格提交确定，{@code idempotencyKey} 支持安全重放。 */
  @PostMapping("/api/issues/{issueId}/review")
  public Result<Void> review(
      @PathVariable("issueId") String issueIdStr, @RequestBody ReviewIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    if (request.getDecision() == null || request.getDecision().isBlank()) {
      throw new IllegalArgumentException("decision must not be blank");
    }
    ReviewDecision decision = parseEnum(request.getDecision(), ReviewDecision.class, "decision");

    issueRunService.reviewByHuman(
        issueId, decision, request.getReason(), request.getIdempotencyKey());

    return Results.ok();
  }

  @PostMapping("/api/issues/{issueId}/cancel")
  public Result<IssueDTO> cancel(
      @PathVariable("issueId") String issueIdStr, @RequestBody CancelIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Issue cancelled = issueService.cancelIssue(issueId, expectedVersion, request.getReason());
    return Results.ok(mapper.toDto(cancelled));
  }

  @PostMapping("/api/issues/{issueId}/retry")
  public ResponseEntity<Result<IssueActivityDTO>> retry(
      @PathVariable("issueId") String issueIdStr, @RequestBody RetryIssueRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID issueId = ProjectDtoMapper.parseUuid(issueIdStr, "issueId");
    IssueActivity activity = issueRunService.retryRun(issueId, request.getIdempotencyKey());
    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(activity)));
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

  /** 按当前职责配置列出该 Issue 的稳定 Agent 归属；职责变更后旧归属仍可见其原始身份。 */
  private List<IssueAgentSessionDTO> listAgentSessions(Issue issue) {
    List<IssueAgentSessionDTO> sessions = new ArrayList<>(2);
    IssueAgentSession executor =
        issue.getAssigneeAgentName() == null
            ? null
            : issueRunService.getAgentSession(issue.getId(), issue.getAssigneeAgentName());
    if (executor != null) {
      sessions.add(mapper.toDto(executor, IssueRunRole.EXECUTOR.name()));
    }
    IssueAgentSession reviewer =
        issue.getReviewerAgentName() == null
            ? null
            : issueRunService.getAgentSession(issue.getId(), issue.getReviewerAgentName());
    if (reviewer != null) {
      sessions.add(mapper.toDto(reviewer, IssueRunRole.REVIEWER.name()));
    }
    return sessions;
  }

  private IssueAgentSession resolveAgentSession(
      Map<String, IssueAgentSession> cache, IssueRun run) {
    if (run == null || run.getAgentName() == null) {
      return null;
    }
    return cache.computeIfAbsent(
        run.getAgentName(),
        agentName -> issueRunService.getAgentSession(run.getIssueId(), agentName));
  }

  /**
   * 计算下一条 Activity 游标：窗口未取满即已到末尾，取满则精确探测其后是否仍有 Activity。
   *
   * <p>返回 null 表示 Activity 已经没有更多，调用方据此停止轮询。
   */
  private String nextActivityCursor(UUID issueId, List<IssueActivity> page, int limit) {
    if (page.size() < limit) {
      return null;
    }
    long lastSequence = page.get(page.size() - 1).getSequence();
    boolean hasMore = !issueService.listActivitiesPage(issueId, lastSequence, 1).isEmpty();
    return hasMore ? String.valueOf(lastSequence) : null;
  }

  private static IssueActivityKind resolveHumanActivityKind(String kind, IssueRunRole targetRole) {
    if (kind == null || kind.isBlank()) {
      return targetRole != null ? IssueActivityKind.INSTRUCTION : IssueActivityKind.COMMENT;
    }
    IssueActivityKind parsed = parseEnum(kind, IssueActivityKind.class, "kind");
    if (!HUMAN_WRITABLE_KINDS.contains(parsed)) {
      throw new IllegalArgumentException("kind is invalid");
    }
    if (parsed == IssueActivityKind.INSTRUCTION && targetRole == null) {
      throw new IllegalArgumentException("INSTRUCTION requires targetRole");
    }
    return parsed;
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
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(fieldName + " is invalid");
    }
  }
}
