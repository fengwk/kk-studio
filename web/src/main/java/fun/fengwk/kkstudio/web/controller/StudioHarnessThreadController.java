package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
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

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐。类型化 runtime 拒绝在此统一翻译：未找到 {@literal ->} 404、业务冲突 {@literal ->} 409、非法请求/DTO
 * {@literal ->} 400。
 */
@RestController
@RequestMapping("/api/harness/threads")
public class StudioHarnessThreadController {
  private final HarnessRuntime runtime;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(HarnessRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
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

  /** 直接重命名 Thread（name 由 Core 权威规范化；同名 no-op、version 精确 +1 仅在实际改名时发生），返回权威当前 Thread。 */
  @PutMapping("/{threadId}/name")
  public Result<HarnessThreadDTO> rename(
      @PathVariable String threadId, @RequestBody HarnessNameUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              ThreadState updated =
                  runtime.renameThread(
                      HarnessRuntimeRequestMapper.toRenameThreadCommand(threadId, request));
              return HarnessRuntimeResponseMapper.toThreadDto(
                  runtime.getThreadSnapshot(updated.id()));
            }));
  }

  /** 直接调用 HarnessRuntime 执行受 expectedVersion 守护的手动压缩；返回 202 Accepted。 */
  @PostMapping("/{threadId}/compact")
  public Result<HarnessThreadCompactResultDTO> compact(
      @PathVariable String threadId, @RequestBody HarnessThreadCompactDTO request) {
    return Results.accepted(
        withRuntimeTranslation(
            () -> {
              CompactThreadResult result =
                  runtime.compactThread(
                      HarnessRuntimeRequestMapper.toCompactThreadCommand(threadId, request));
              return HarnessRuntimeResponseMapper.toCompactResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /**
   * 直接更新 Thread YOLO policy（version CAS）：相同值在任何 CAS 之前 no-op 成功，值变化时 version 精确 +1；不创建
   * Command/Entry/Work、不唤醒 processors。返回权威当前 Thread（与 stop 一致）。
   */
  @PutMapping("/{threadId}/yolo")
  public Result<HarnessThreadDTO> updateYolo(
      @PathVariable String threadId, @RequestBody HarnessThreadYoloUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              ThreadState updated =
                  runtime.setThreadYolo(
                      HarnessRuntimeRequestMapper.toSetThreadYoloCommand(threadId, request));
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
              StopResult result =
                  runtime.stop(HarnessRuntimeRequestMapper.toStopCommand(threadId, request));
              return HarnessRuntimeResponseMapper.toStopResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /** 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。 */
  @PutMapping("/{threadId}/tool-invocations/{toolInvocationId}/approval")
  public Result<ToolInvocationDTO> decideApproval(
      @PathVariable String threadId,
      @PathVariable String toolInvocationId,
      @RequestBody HarnessToolApprovalDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () ->
                HarnessRuntimeResponseMapper.toToolInvocationDto(
                    runtime.decideToolApproval(
                        HarnessRuntimeRequestMapper.toToolApprovalCommand(
                            threadId, toolInvocationId, request)))));
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
