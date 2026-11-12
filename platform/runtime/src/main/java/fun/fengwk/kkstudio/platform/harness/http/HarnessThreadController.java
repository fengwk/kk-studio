package fun.fengwk.kkstudio.platform.harness.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessExecutionAbandonDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>直接返回资源并显式使用 200/202；类型化 runtime 拒绝由统一 advice 翻译：未找到 {@literal ->} 404、业务冲突 {@literal ->} 409。
 * 认证、实际资源权限与请求格式是硬边界，业务规则由对应业务工作流显式编排。
 */
@RestController
@RequestMapping("/api/harness/threads")
public class HarnessThreadController {

  private final HarnessRuntime runtime;
  private final HarnessThreadService threadService;
  private final InteractionService interactionService;

  public HarnessThreadController(
      HarnessRuntime runtime,
      HarnessThreadService threadService,
      InteractionService interactionService) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.threadService = Objects.requireNonNull(threadService, "threadService");
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
  }

  /**
   * 查询目标 Thread 所属执行树中的普通用户根节点；子代理通过独立的 subagents 端点读取。
   *
   * <p>纯查询，不创建 Command / Entry / Work，也不触碰 version。可见性与 Thread 快照相同。
   */
  @GetMapping("/{threadId}/tree")
  public List<HarnessThreadTreeNodeDTO> getTree(@PathVariable UUID threadId) {
    return HarnessRuntimeResponseMapper.toThreadTreeDtos(
        runtime.getThreadTree(threadId).stream()
            .filter(node -> node.thread().kind() == ThreadKind.USER)
            .toList());
  }

  /** 查询父 Thread 的直接 SUBAGENT 子线程。 */
  @GetMapping("/{threadId}/subagents")
  public List<HarnessThreadTreeNodeDTO> getSubagents(@PathVariable UUID threadId) {
    List<UUID> subagentIds =
        runtime.listSubagentsByParent(threadId).stream().map(ThreadState::id).toList();
    if (subagentIds.isEmpty()) {
      return List.of();
    }
    return HarnessRuntimeResponseMapper.toThreadTreeDtos(
        runtime.getThreadTree(threadId).stream()
            .filter(node -> subagentIds.contains(node.thread().id()))
            .toList());
  }

  /**
   * 查询一个一致性的 Thread 快照（单事务）。
   *
   * <p>{@code afterEntryId} 为空时返回完整历史。canonical 祖先游标只返回其后的 Entry，费用只投影这次返回的 slice。当前 Thread、
   * 队列、输出、工具与退出事实始终完整。不计算压缩 advisory。
   */
  @GetMapping("/{threadId}")
  public HarnessThreadSnapshotDTO getSnapshot(
      @PathVariable UUID threadId,
      @RequestParam(name = "afterEntryId", required = false) UUID afterEntryId) {
    return threadService.getSnapshot(threadId, afterEntryId);
  }

  /**
   * 按需查询手动压缩可用性。
   *
   * <p>调用现有完整压缩判断，保留真实 token 阈值、ownership 和模型判断。结果是瞬时 advisory，不是 compact 提交许可或 ETag。
   */
  @GetMapping("/{threadId}/compaction-availability")
  public HarnessManualCompactionDTO getCompactionAvailability(@PathVariable UUID threadId) {
    return HarnessRuntimeResponseMapper.toManualCompactionDto(
        runtime.manualCompactionAvailability(threadId));
  }

  /**
   * 显式放弃一个具体执行代次的退出确认；只解除收敛阻塞，不冒充物理退出或取消执行。
   *
   * <p>请求形状先严格校验；认证与资源读取边界与快照查询一致，不额外限制为执行根，否则 H1 子任务将永久没有放弃出口。首次放弃要求 STOPPED 与 expectedVersion
   * 精确匹配，重复放弃或已真实确认是安全 replay。
   */
  @PostMapping("/{threadId}/executions/{executionId}/abandon")
  public HarnessThreadSnapshotDTO abandonExecution(
      @PathVariable UUID threadId,
      @PathVariable UUID executionId,
      @RequestBody HarnessExecutionAbandonDTO request) {
    HarnessRuntimeRequestMapper.AbandonExecutionTarget target =
        HarnessRuntimeRequestMapper.toAbandonExecutionTarget(
            threadId.toString(), executionId.toString(), request);
    return threadService.abandonExecution(
        target.threadId(), target.executionId(), target.expectedVersion());
  }

  /**
   * 直接重命名 Thread（name 由 Core 权威规范化；同名 no-op、version 精确 +1 仅在实际改名时发生），返回权威当前 Thread。
   *
   * <p>认证、实际资源权限与请求形状是硬边界，业务命名规则由对应业务工作流显式维护。
   */
  @PutMapping("/{threadId}/name")
  public HarnessThreadDTO rename(
      @PathVariable UUID threadId, @RequestBody HarnessNameUpdateDTO request) {
    RenameThreadCommand command =
        HarnessRuntimeRequestMapper.toRenameThreadCommand(threadId.toString(), request);
    return threadService.rename(command);
  }

  /**
   * 直接调用 HarnessRuntime 执行受 expectedVersion 守护的手动压缩；返回 202 Accepted。
   *
   * <p>压缩会产生 Turn / model invocation / 后续 work；通用入口校验认证与请求格式。
   */
  @PostMapping("/{threadId}/compact")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public HarnessThreadCompactResultDTO compact(
      @PathVariable UUID threadId, @RequestBody HarnessThreadCompactDTO request) {
    CompactThreadCommand command =
        HarnessRuntimeRequestMapper.toCompactThreadCommand(threadId.toString(), request);
    return threadService.compact(command);
  }

  /**
   * 直接更新 Thread YOLO policy：相同值 no-op 成功，值变化时 version 精确 +1，不与完整 Thread version 做 CAS；不创建
   * Command/Entry/Work、不唤醒 processors。返回权威当前 Thread（与 stop 一致）。
   *
   * <p>请求形状先经 {@link HarnessRuntimeRequestMapper} 严格校验（非 canonical UUID -&gt; 400）；缺失 Thread 由
   * {@link HarnessRuntime} 翻译为 404。业务默认策略由对应业务工作流显式维护。
   */
  @PutMapping("/{threadId}/yolo")
  public HarnessThreadDTO updateYolo(
      @PathVariable UUID threadId, @RequestBody HarnessThreadYoloUpdateDTO request) {
    SetThreadYoloCommand command =
        HarnessRuntimeRequestMapper.toSetThreadYoloCommand(threadId.toString(), request);
    return threadService.updateYolo(command);
  }

  /** 停止当前 Thread 与完整后代，返回聚合回执（stopRequestId + version CAS 幂等）。 */
  @PostMapping("/{threadId}/stop")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public HarnessThreadStopResultDTO stop(
      @PathVariable UUID threadId, @RequestBody HarnessThreadStopDTO request) {
    StopCommand command = HarnessRuntimeRequestMapper.toStopCommand(threadId.toString(), request);
    return threadService.stop(command);
  }

  /**
   * 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。
   *
   * <p>操作者只取自服务端认证上下文；请求形状与资源归属照常校验。
   */
  @PutMapping("/{threadId}/tool-invocations/{toolInvocationId}/approval")
  public ToolInvocationDTO decideApproval(
      @PathVariable UUID threadId,
      @PathVariable UUID toolInvocationId,
      @RequestBody HarnessToolApprovalDTO request,
      HttpServletRequest httpRequest) {
    String operator = OperatorResolver.resolve(httpRequest);
    return HarnessRuntimeResponseMapper.toToolInvocationDto(
        interactionService.decideApproval(
            HarnessRuntimeRequestMapper.toToolApprovalCommand(
                threadId.toString(), toolInvocationId.toString(), request, operator)));
  }
}
