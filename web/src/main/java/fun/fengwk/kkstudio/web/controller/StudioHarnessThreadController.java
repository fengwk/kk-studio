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

import fun.fengwk.kkstudio.core.ai.runtime.task.SystemPromptPreviewService;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSystemPromptPreviewDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeWebMapper;

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
@RequestMapping("/api/ai/runtime/threads")
public class StudioHarnessThreadController {
  private final HarnessRuntime runtime;
  private final ThreadProcessor threadProcessor;
  private final SystemPromptPreviewService systemPromptPreviewService;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessRuntime runtime,
      ThreadProcessor threadProcessor,
      SystemPromptPreviewService systemPromptPreviewService) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.threadProcessor = Objects.requireNonNull(threadProcessor, "threadProcessor");
    this.systemPromptPreviewService =
        Objects.requireNonNull(systemPromptPreviewService, "systemPromptPreviewService");
  }

  /** 查询一个一致性的 Thread 快照（单事务）。 */
  @GetMapping("/{threadId}/snapshot")
  public Result<HarnessThreadSnapshotDTO> getSnapshot(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
              ThreadSnapshot snapshot = runtime.getThreadSnapshot(id);
              ManualCompactionAvailability availability =
                  threadProcessor.manualCompactionAvailability(id);
              return HarnessRuntimeWebMapper.toSnapshotDto(snapshot, availability);
            }));
  }

  /** 按当前 branch 最新 Agent / Environment 现算系统提示词预览。进入 Debug 与 turn 结束后由前端按需读取。 */
  @GetMapping("/{threadId}/system-prompt")
  public Result<HarnessSystemPromptPreviewDTO> getSystemPrompt(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
              HarnessSystemPromptPreviewDTO dto = new HarnessSystemPromptPreviewDTO();
              dto.setText(systemPromptPreviewService.preview(id));
              return dto;
            }));
  }

  /** 直接调用 ThreadProcessor 执行受 expectedRevision 守护的手动压缩。 */
  @PostMapping("/{threadId}/compact")
  public Result<HarnessThreadCompactResultDTO> compact(
      @PathVariable String threadId, @RequestBody HarnessThreadCompactDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              CompactThreadResult result =
                  threadProcessor.compactThread(
                      HarnessRuntimeWebMapper.toCompactThreadCommand(threadId, request));
              return HarnessRuntimeWebMapper.toCompactResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /**
   * 直接更新 Thread YOLO policy（revision CAS）：相同值在任何 CAS 之前 no-op 成功，值变化时 revision 精确 +1；不创建
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
                      HarnessRuntimeWebMapper.toSetThreadYoloCommand(threadId, request));
              return HarnessRuntimeWebMapper.toThreadDto(runtime.getThreadSnapshot(updated.id()));
            }));
  }

  /** 原子停止当前 Turn 并取消 queued Commands（stopRequestId + revision CAS 幂等）。 */
  @PostMapping("/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              StopResult result =
                  runtime.stop(HarnessRuntimeWebMapper.toStopCommand(threadId, request));
              return HarnessRuntimeWebMapper.toStopResultDto(
                  result, runtime.getThreadSnapshot(result.thread().id()));
            }));
  }

  /** 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。 */
  @PostMapping("/{threadId}/tool-invocations/{toolInvocationId}/approval")
  public Result<ToolInvocationDTO> decideApproval(
      @PathVariable String threadId,
      @PathVariable String toolInvocationId,
      @RequestBody HarnessToolApprovalDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () ->
                HarnessRuntimeWebMapper.toToolInvocationDto(
                    runtime.decideToolApproval(
                        HarnessRuntimeWebMapper.toToolApprovalCommand(
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
