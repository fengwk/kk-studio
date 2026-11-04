package fun.fengwk.kkstudio.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessExecutionAbandonDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugRequestDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadTreeNodeDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.api.OperatorResolver;
import fun.fengwk.kkstudio.web.harness.HarnessThreadService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.List;
import java.util.Objects;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>统一返回 {@link ApiResponse}，HTTP 状态由 {@code ApiResponseStatusAdvice} 按 {@code response.status}
 * 对齐。类型化 runtime 拒绝在此统一翻译：未找到 {@literal ->} 404、业务冲突 {@literal ->} 409、非法请求/DTO {@literal ->}
 * 400。认证、实际资源权限与请求格式是硬边界，业务规则由对应业务工作流显式编排。
 */
@RestController
@RequestMapping("/api/threads")
public class StudioHarnessThreadController {

  private final HarnessRuntime runtime;
  private final HarnessThreadService threadService;
  private final ModelRequestDebugService modelRequestDebugService;
  private final InteractionService interactionService;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessRuntime runtime,
      HarnessThreadService threadService,
      ModelRequestDebugService modelRequestDebugService,
      InteractionService interactionService) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.threadService = Objects.requireNonNull(threadService, "threadService");
    this.modelRequestDebugService =
        Objects.requireNonNull(modelRequestDebugService, "modelRequestDebugService");
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
  }

  /**
   * 查询目标 Thread 所属执行树：任意节点返回同一真实根下的完整节点列表。
   *
   * <p>纯查询，不创建 Command / Entry / Work，也不触碰 version。可见性与 Thread 快照相同。
   */
  @GetMapping("/{threadId}/tree")
  public ApiResponse<List<HarnessThreadTreeNodeDTO>> getTree(@PathVariable String threadId) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () ->
                HarnessRuntimeResponseMapper.toThreadTreeDtos(
                    runtime.getThreadTree(Uuids.parseCanonical(threadId, "threadId")))));
  }

  /**
   * 查询一个一致性的 Thread 快照（单事务）。
   *
   * <p>{@code afterEntryId} 为空时返回完整历史。canonical 祖先游标只返回其后的 Entry，费用只投影这次返回的 slice。当前 Thread、
   * 队列、输出、工具与退出事实始终完整。不计算压缩 advisory。
   */
  @GetMapping("/{threadId}")
  public ApiResponse<HarnessThreadSnapshotDTO> getSnapshot(
      @PathVariable String threadId, @RequestParam(required = false) String afterEntryId) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () ->
                threadService.getSnapshot(
                    Uuids.parseCanonical(threadId, "threadId"),
                    afterEntryId == null
                        ? null
                        : Uuids.parseCanonical(afterEntryId, "afterEntryId"))));
  }

  /**
   * 按需查询手动压缩可用性。
   *
   * <p>调用现有完整压缩判断，保留真实 token 阈值、ownership 和模型判断。结果是瞬时 advisory，不是 compact 提交许可或 ETag。
   */
  @GetMapping("/{threadId}/compaction-availability")
  public ApiResponse<HarnessManualCompactionDTO> getCompactionAvailability(
      @PathVariable String threadId) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () ->
                HarnessRuntimeResponseMapper.toManualCompactionDto(
                    runtime.manualCompactionAvailability(
                        Uuids.parseCanonical(threadId, "threadId")))));
  }

  /**
   * 显式放弃一个具体执行代次的退出确认；只解除收敛阻塞，不冒充物理退出或取消执行。
   *
   * <p>请求形状先严格校验；认证与资源读取边界与快照查询一致，不额外限制为执行根，否则 H1 子任务将永久没有放弃出口。首次放弃要求 STOPPED 与 expectedVersion
   * 精确匹配，重复放弃或已真实确认是安全 replay。
   */
  @PostMapping("/{threadId}/executions/{executionId}/abandon")
  public ApiResponse<HarnessThreadSnapshotDTO> abandonExecution(
      @PathVariable String threadId,
      @PathVariable String executionId,
      @RequestBody HarnessExecutionAbandonDTO request) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              HarnessRuntimeRequestMapper.AbandonExecutionTarget target =
                  HarnessRuntimeRequestMapper.toAbandonExecutionTarget(
                      threadId, executionId, request);
              return threadService.abandonExecution(
                  target.threadId(), target.executionId(), target.expectedVersion());
            }));
  }

  /**
   * 按 UI 当前草稿 model / environment 现算结构化 Model Request Debug 投影（read-only）：与正式 Turn 共用同一 planner，
   * 不检查 branch HEAD、不发布 Package、不触发 sync、不产生写入，也不改动任何持久事实。
   *
   * <p>请求体与 UUID 形状由 {@link HarnessRuntimeRequestMapper} 严格校验（model 必填、environmentName 可空
   * canonical、未知字段 -&gt; 400），缺失 Thread 由 {@link HarnessRuntime} 的 typed 异常翻译为 404。
   */
  @PostMapping("/{threadId}/model-request-debug")
  public ApiResponse<HarnessModelRequestDebugDTO> previewModelRequest(
      @PathVariable String threadId, @RequestBody HarnessModelRequestDebugRequestDTO request) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              HarnessRuntimeRequestMapper.ModelRequestDebugSelection selection =
                  HarnessRuntimeRequestMapper.toModelRequestDebugSelection(request);
              return modelRequestDebugService.getModelRequestDebug(
                  Uuids.parseCanonical(threadId, "threadId"),
                  selection.model(),
                  selection.environmentName());
            }));
  }

  /**
   * 直接重命名 Thread（name 由 Core 权威规范化；同名 no-op、version 精确 +1 仅在实际改名时发生），返回权威当前 Thread。
   *
   * <p>认证、实际资源权限与请求形状是硬边界，业务命名规则由对应业务工作流显式维护。
   */
  @PutMapping("/{threadId}/name")
  public ApiResponse<HarnessThreadDTO> rename(
      @PathVariable String threadId, @RequestBody HarnessNameUpdateDTO request) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              RenameThreadCommand command =
                  HarnessRuntimeRequestMapper.toRenameThreadCommand(threadId, request);
              return threadService.rename(command);
            }));
  }

  /**
   * 直接调用 HarnessRuntime 执行受 expectedVersion 守护的手动压缩；返回 202 Accepted。
   *
   * <p>压缩会产生 Turn / model invocation / 后续 work；通用入口校验认证与请求格式。
   */
  @PostMapping("/{threadId}/compact")
  public ApiResponse<HarnessThreadCompactResultDTO> compact(
      @PathVariable String threadId, @RequestBody HarnessThreadCompactDTO request) {
    return ApiResponse.accepted(
        RuntimeErrors.translate(
            () -> {
              CompactThreadCommand command =
                  HarnessRuntimeRequestMapper.toCompactThreadCommand(threadId, request);
              return threadService.compact(command);
            }));
  }

  /**
   * 直接更新 Thread YOLO policy：相同值 no-op 成功，值变化时 version 精确 +1，不与完整 Thread version 做 CAS；不创建
   * Command/Entry/Work、不唤醒 processors。返回权威当前 Thread（与 stop 一致）。
   *
   * <p>请求形状先经 {@link HarnessRuntimeRequestMapper} 严格校验（非 canonical UUID -&gt; 400）；缺失 Thread 由
   * {@link HarnessRuntime} 翻译为 404。业务默认策略由对应业务工作流显式维护。
   */
  @PutMapping("/{threadId}/yolo")
  public ApiResponse<HarnessThreadDTO> updateYolo(
      @PathVariable String threadId, @RequestBody HarnessThreadYoloUpdateDTO request) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              SetThreadYoloCommand command =
                  HarnessRuntimeRequestMapper.toSetThreadYoloCommand(threadId, request);
              return threadService.updateYolo(command);
            }));
  }

  /** 停止当前 Thread 与完整后代，返回聚合回执（stopRequestId + version CAS 幂等）。 */
  @PostMapping("/{threadId}/stop")
  public ApiResponse<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              StopCommand command = HarnessRuntimeRequestMapper.toStopCommand(threadId, request);
              return threadService.stop(command);
            }));
  }

  /**
   * 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。
   *
   * <p>操作者只取自服务端认证上下文；请求形状与资源归属照常校验。
   */
  @PutMapping("/{threadId}/tool-invocations/{toolInvocationId}/approval")
  public ApiResponse<ToolInvocationDTO> decideApproval(
      @PathVariable String threadId,
      @PathVariable String toolInvocationId,
      @RequestBody HarnessToolApprovalDTO request,
      HttpServletRequest httpRequest) {
    String operator = OperatorResolver.resolve(httpRequest);
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () ->
                HarnessRuntimeResponseMapper.toToolInvocationDto(
                    interactionService.decideApproval(
                        HarnessRuntimeRequestMapper.toToolApprovalCommand(
                            threadId, toolInvocationId, request, operator)))));
  }
}
