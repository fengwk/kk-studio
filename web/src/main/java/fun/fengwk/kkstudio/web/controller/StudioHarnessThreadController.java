package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.harness.thread.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.platform.project.tool.ProjectThreadOwnerResolver;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐。类型化 runtime 拒绝在此统一翻译：未找到 {@literal ->} 404、业务冲突 {@literal ->} 409、非法请求/DTO
 * {@literal ->} 400。属于 Issue+Agent 的 Thread（含其分支）不接受本控制面的 YOLO 覆盖，由 Project 设置与 Issue 工作流统一维护（409）。
 */
@RestController
@RequestMapping("/api/harness/threads")
public class StudioHarnessThreadController {

  /** Issue Agent Branch 的 YOLO 由 Project 启动策略与 Issue 工作流统一维护，通用 Branch API 不得覆盖。 */
  private static final String ISSUE_AGENT_BRANCH_YOLO_OWNED_BY_PROJECT =
      "Issue Agent Branch YOLO is owned by the project settings";

  /** 单用户部署边界下没有认证主体时使用的固定操作者，与问卷输入使用同一服务端身份来源。 */
  private static final String LOCAL_OPERATOR = "local-user";

  private final HarnessRuntime runtime;
  private final ModelRequestDebugService modelRequestDebugService;
  private final ProjectThreadOwnerResolver projectThreadOwnerResolver;
  private final InteractionService interactionService;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessRuntime runtime,
      ModelRequestDebugService modelRequestDebugService,
      ProjectThreadOwnerResolver projectThreadOwnerResolver,
      InteractionService interactionService) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.modelRequestDebugService =
        Objects.requireNonNull(modelRequestDebugService, "modelRequestDebugService");
    this.projectThreadOwnerResolver =
        Objects.requireNonNull(projectThreadOwnerResolver, "projectThreadOwnerResolver");
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
  }

  /** 查询一个一致性的 Thread 快照（单事务）。 */
  @GetMapping("/{threadId}")
  public Result<HarnessThreadSnapshotDTO> getSnapshot(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeRequestMapper.parseUuid(threadId, "threadId");
              ThreadSnapshot snapshot = runtime.getThreadSnapshot(id);
              ManualCompactionAvailability availability = runtime.manualCompactionAvailability(id);
              return HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, availability);
            }));
  }

  /**
   * 现算结构化 Model Request Debug 投影（read-only）：不检查 branch HEAD、不发布 Package、不触发 sync、不产生写入。
   *
   * <p>请求体与 UUID 形状由 {@link HarnessRuntimeRequestMapper#parseUuid} 严格校验（非 canonical UUID -&gt;
   * 400），缺失 Thread 由 {@link HarnessRuntime} 的 typed 异常翻译为 404。
   */
  @GetMapping("/{threadId}/model-request-debug")
  public Result<HarnessModelRequestDebugDTO> getModelRequestDebug(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () ->
                modelRequestDebugService.getModelRequestDebug(
                    HarnessRuntimeRequestMapper.parseUuid(threadId, "threadId"))));
  }

  /**
   * 直接重命名 Thread（name 由 Core 权威规范化；同名 no-op、version 精确 +1 仅在实际改名时发生），返回权威当前 Thread。
   *
   * <p>属于 Issue+Agent 的 Thread（含其分支）的名称由 Issue 工作流统一维护，因此这里先严格校验请求，再以 409 拒绝且绝不触达 {@link
   * HarnessRuntime}。Chat Thread 不受影响。
   */
  @PutMapping("/{threadId}/name")
  public Result<HarnessThreadDTO> rename(
      @PathVariable String threadId, @RequestBody HarnessNameUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              RenameThreadCommand command =
                  HarnessRuntimeRequestMapper.toRenameThreadCommand(threadId, request);
              rejectIssueAgentBranch(
                  command.threadId(), "Issue Agent Branch rename is owned by the Issue workflow");
              ThreadState updated = runtime.renameThread(command);
              return HarnessRuntimeResponseMapper.toThreadDto(
                  runtime.getThreadSnapshot(updated.id()));
            }));
  }

  /**
   * 直接调用 HarnessRuntime 执行受 expectedVersion 守护的手动压缩；返回 202 Accepted。
   *
   * <p>压缩会产生 Turn / model invocation / 后续 work，因此 Issue+Agent Thread 必须先经过 Issue 工作流的产品锁，
   * 通用入口在严格校验后直接 409，不允许在 Issue 暂停或归档期间直达 Runtime。
   */
  @PostMapping("/{threadId}/compact")
  public Result<HarnessThreadCompactResultDTO> compact(
      @PathVariable String threadId, @RequestBody HarnessThreadCompactDTO request) {
    return Results.accepted(
        withRuntimeTranslation(
            () -> {
              CompactThreadCommand command =
                  HarnessRuntimeRequestMapper.toCompactThreadCommand(threadId, request);
              rejectIssueAgentBranch(
                  command.threadId(), "Issue Agent Branch compact is owned by the Issue workflow");
              CompactThreadResult result = runtime.compactThread(command);
              return HarnessRuntimeResponseMapper.toCompactResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /**
   * 直接更新 Thread YOLO policy：相同值 no-op 成功，值变化时 version 精确 +1，不与完整 Thread version 做 CAS；不创建
   * Command/Entry/Work、不唤醒 processors。返回权威当前 Thread（与 stop 一致）。
   *
   * <p>Chat/Canvas Thread 是本控制面的归属范围；属于 Issue Agent Session 的 Thread（含其任何分支，且不要求存在活动 Run） 的 YOLO 由
   * Project 启动策略与内部对齐维护，因此这里以 409 拒绝且绝不触达 {@link HarnessRuntime}。请求形状仍先经 {@link
   * HarnessRuntimeRequestMapper} 严格校验（非 canonical UUID -&gt; 400）。缺失 Thread 的 resolver 判定为 false，
   * 保持 {@link HarnessRuntime} 的 404 翻译。
   */
  @PutMapping("/{threadId}/yolo")
  public Result<HarnessThreadDTO> updateYolo(
      @PathVariable String threadId, @RequestBody HarnessThreadYoloUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              SetThreadYoloCommand command =
                  HarnessRuntimeRequestMapper.toSetThreadYoloCommand(threadId, request);
              rejectIssueAgentBranch(command.threadId(), ISSUE_AGENT_BRANCH_YOLO_OWNED_BY_PROJECT);
              ThreadState updated = runtime.setThreadYolo(command);
              return HarnessRuntimeResponseMapper.toThreadDto(
                  runtime.getThreadSnapshot(updated.id()));
            }));
  }

  /** 原子停止当前 Turn 并取消 queued Commands（stopRequestId + version CAS 幂等）。 */
  @PostMapping("/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              StopCommand command = HarnessRuntimeRequestMapper.toStopCommand(threadId, request);
              rejectIssueAgentBranch(
                  command.threadId(), "Issue Agent Branch stop is owned by the Issue workflow");
              StopResult result = runtime.stop(command);
              return HarnessRuntimeResponseMapper.toStopResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /**
   * 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。
   *
   * <p>审批与问卷回答共用 platform 交互服务的产品锁序与门禁：Issue+Agent Thread 先按 {@code Project SHARE -> Issue UPDATE}
   * 锁定产品层级，再进入 Harness Runtime 决策，因此审批入口同样不能绕过 Issue 的暂停/归档约束。操作者只取自服务端认证上下文。
   */
  @PutMapping("/{threadId}/tool-invocations/{toolInvocationId}/approval")
  public Result<ToolInvocationDTO> decideApproval(
      @PathVariable String threadId,
      @PathVariable String toolInvocationId,
      @RequestBody HarnessToolApprovalDTO request,
      HttpServletRequest httpRequest) {
    String operator = resolveOperator(httpRequest);
    return Results.ok(
        withRuntimeTranslation(
            () ->
                HarnessRuntimeResponseMapper.toToolInvocationDto(
                    interactionService.decideApproval(
                        HarnessRuntimeRequestMapper.toToolApprovalCommand(
                            threadId, toolInvocationId, request, operator)))));
  }

  /**
   * Issue+Agent Thread 的通用写入口在请求形状校验之后、Runtime 之前拒绝。缺失 Thread 的 resolver 判定为 false， 仍交给 {@link
   * HarnessRuntime} 翻译为 404。
   */
  private void rejectIssueAgentBranch(UUID threadId, String reason) {
    if (projectThreadOwnerResolver.isIssueAgentBranch(threadId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
  }

  /** 操作者只取自部署边界建立的认证主体；没有主体时回退到单用户边界的固定操作者，绝不信任客户端传入的身份。 */
  private static String resolveOperator(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal != null) {
      String name = principal.getName();
      if (name != null && !name.isBlank()) {
        return name.strip();
      }
    }
    return LOCAL_OPERATOR;
  }

  /** 将 Harness Runtime 异常翻译为统一 HTTP 错误响应。 */
  static <T> T withRuntimeTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (HarnessRuntimeNotFoundException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (HarnessRuntimeConflictException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
