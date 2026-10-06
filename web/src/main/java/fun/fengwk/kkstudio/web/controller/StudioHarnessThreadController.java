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
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;
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
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐。类型化 runtime 拒绝在此统一翻译：未找到 {@literal ->} 404、业务冲突 {@literal ->} 409、非法请求/DTO
 * {@literal ->} 400。控制面不再按 Thread 的产品归属（Issue/Chat）拒绝：认证、实际资源权限与请求格式仍是硬边界，业务规则由对应业务工作流显式编排。
 */
@RestController
@RequestMapping("/api/harness/threads")
public class StudioHarnessThreadController {

  /** 单用户部署边界下没有认证主体时使用的固定操作者，与问卷输入使用同一服务端身份来源。 */
  private static final String LOCAL_OPERATOR = "local-user";

  private final HarnessRuntime runtime;
  private final ModelRequestDebugService modelRequestDebugService;
  private final InteractionService interactionService;
  private final UsageCostProjectionService usageCostProjectionService;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessRuntime runtime,
      ModelRequestDebugService modelRequestDebugService,
      InteractionService interactionService,
      UsageCostProjectionService usageCostProjectionService) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.modelRequestDebugService =
        Objects.requireNonNull(modelRequestDebugService, "modelRequestDebugService");
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
    this.usageCostProjectionService =
        Objects.requireNonNull(usageCostProjectionService, "usageCostProjectionService");
  }

  /**
   * 查询目标 Thread 所属执行树：任意节点返回同一真实根下的完整节点列表。
   *
   * <p>纯查询，不创建 Command / Entry / Work，也不触碰 version。可见性与 Thread 快照相同。
   */
  @GetMapping("/{threadId}/tree")
  public Result<List<HarnessThreadTreeNodeDTO>> getTree(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () ->
                HarnessRuntimeResponseMapper.toThreadTreeDtos(
                    runtime.getThreadTree(
                        HarnessRuntimeRequestMapper.parseUuid(threadId, "threadId")))));
  }

  /** 查询一个一致性的 Thread 快照（单事务）；entryPath 的每个 Entry 都带上读取时费用投影。 */
  @GetMapping("/{threadId}")
  public Result<HarnessThreadSnapshotDTO> getSnapshot(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeRequestMapper.parseUuid(threadId, "threadId");
              ThreadSnapshot snapshot = runtime.getThreadSnapshot(id);
              ManualCompactionAvailability availability = runtime.manualCompactionAvailability(id);
              Map<UUID, HarnessUsageCostDTO> usageCosts =
                  usageCostProjectionService.project(snapshot.entryPath().entries());
              return HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, availability, usageCosts);
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
   * <p>认证、实际资源权限与请求形状是硬边界；不再按 Thread 所属产品拒绝，业务命名规则由对应业务工作流显式维护。
   */
  @PutMapping("/{threadId}/name")
  public Result<HarnessThreadDTO> rename(
      @PathVariable String threadId, @RequestBody HarnessNameUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              RenameThreadCommand command =
                  HarnessRuntimeRequestMapper.toRenameThreadCommand(threadId, request);
              ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
              ThreadState updated = runtime.renameThread(command);
              return HarnessRuntimeResponseMapper.toThreadDto(
                  runtime.getThreadSnapshot(updated.id()));
            }));
  }

  /**
   * 直接调用 HarnessRuntime 执行受 expectedVersion 守护的手动压缩；返回 202 Accepted。
   *
   * <p>压缩会产生 Turn / model invocation / 后续 work；通用入口不再按 Thread 归属拒绝，认证与请求格式照常校验。
   */
  @PostMapping("/{threadId}/compact")
  public Result<HarnessThreadCompactResultDTO> compact(
      @PathVariable String threadId, @RequestBody HarnessThreadCompactDTO request) {
    return Results.accepted(
        withRuntimeTranslation(
            () -> {
              CompactThreadCommand command =
                  HarnessRuntimeRequestMapper.toCompactThreadCommand(threadId, request);
              ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
              CompactThreadResult result = runtime.compactThread(command);
              return HarnessRuntimeResponseMapper.toCompactResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /**
   * 直接更新 Thread YOLO policy：相同值 no-op 成功，值变化时 version 精确 +1，不与完整 Thread version 做 CAS；不创建
   * Command/Entry/Work、不唤醒 processors。返回权威当前 Thread（与 stop 一致）。
   *
   * <p>请求形状先经 {@link HarnessRuntimeRequestMapper} 严格校验（非 canonical UUID -&gt; 400）；缺失 Thread 由
   * {@link HarnessRuntime} 翻译为 404。控制面不再按产品归属拒绝，业务默认策略由对应业务工作流显式维护。
   */
  @PutMapping("/{threadId}/yolo")
  public Result<HarnessThreadDTO> updateYolo(
      @PathVariable String threadId, @RequestBody HarnessThreadYoloUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              SetThreadYoloCommand command =
                  HarnessRuntimeRequestMapper.toSetThreadYoloCommand(threadId, request);
              ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
              ThreadState updated = runtime.setThreadYolo(command);
              return HarnessRuntimeResponseMapper.toThreadDto(
                  runtime.getThreadSnapshot(updated.id()));
            }));
  }

  /** 停止当前 Thread 与完整后代，返回聚合回执（stopRequestId + version CAS 幂等）。 */
  @PostMapping("/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              StopCommand command = HarnessRuntimeRequestMapper.toStopCommand(threadId, request);
              ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
              StopResult result = runtime.stop(command);
              return HarnessRuntimeResponseMapper.toStopResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /**
   * 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。
   *
   * <p>操作者只取自服务端认证上下文；请求形状与资源归属照常校验，不再按 Thread 产品归属拒绝。
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
