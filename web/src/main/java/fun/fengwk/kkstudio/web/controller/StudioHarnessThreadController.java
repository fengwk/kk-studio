package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeWebMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐；命令入队使用 {@link Results#accepted}（202）。类型化 runtime 拒绝在此统一翻译：未找到 {@literal ->}
 * 404、业务冲突 {@literal ->} 409、非法请求/DTO {@literal ->} 400。
 */
@RestController
@RequestMapping("/api/ai/runtime/threads")
public class StudioHarnessThreadController {
  private final HarnessRuntime runtime;
  private final RealtimeEventTail realtimeEventTail;
  private final ThreadRevisionSseHub revisionHub;
  private final Executor eventStreamExecutor;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessRuntime runtime,
      RealtimeEventTail realtimeEventTail,
      ThreadRevisionSseHub revisionHub,
      @Qualifier("harnessEventStreamTaskExecutor") Executor eventStreamExecutor) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.realtimeEventTail = Objects.requireNonNull(realtimeEventTail, "realtimeEventTail");
    this.revisionHub = Objects.requireNonNull(revisionHub, "revisionHub");
    this.eventStreamExecutor = Objects.requireNonNull(eventStreamExecutor, "eventStreamExecutor");
  }

  /** 查询一个一致性的 Thread 快照（单事务）。 */
  @GetMapping("/{threadId}/snapshot")
  public Result<HarnessThreadSnapshotDTO> getSnapshot(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
              return HarnessRuntimeWebMapper.toSnapshotDto(runtime.getThreadSnapshot(id));
            }));
  }

  /**
   * 将一个 typed command batch 原子入队；202 仅表示已接受，不代表模型已完成。所有 8 类命令由 mapper 按 discriminator 严格校验后映射为一个
   * {@link ThreadCommandBatch}。
   */
  @PostMapping("/{threadId}/commands")
  public Result<List<HarnessThreadCommandDTO>> enqueueCommands(
      @PathVariable String threadId, @RequestBody HarnessThreadCommandBatchDTO batchDTO) {
    List<HarnessThreadCommandDTO> dto =
        withRuntimeTranslation(
            () -> {
              ThreadCommandBatch batch = HarnessRuntimeWebMapper.toCommandBatch(threadId, batchDTO);
              List<ThreadCommand> commands = runtime.enqueueCommands(batch);
              List<HarnessThreadCommandDTO> mapped = new ArrayList<>(commands.size());
              for (ThreadCommand command : commands) {
                mapped.add(HarnessRuntimeWebMapper.toCommandDto(command));
              }
              return List.copyOf(mapped);
            });
    return Results.accepted(dto);
  }

  /** 同步重定位 Thread head cursor（revision CAS）。 */
  @PutMapping("/{threadId}/head")
  public Result<HarnessThreadDTO> updateHead(
      @PathVariable String threadId, @RequestBody HarnessThreadHeadUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              ThreadState moved =
                  runtime.moveHead(HarnessRuntimeWebMapper.toMoveHeadCommand(threadId, request));
              return HarnessRuntimeWebMapper.toThreadDto(runtime.getThreadSnapshot(moved.id()));
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
              HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
              dto.setStatus(result.status().name());
              dto.setThread(
                  HarnessRuntimeWebMapper.toThreadDto(
                      runtime.getThreadSnapshot(result.thread().id())));
              dto.setStoppedTurnEndEntryId(
                  result.stoppedTurnEndEntryId() == null
                      ? null
                      : result.stoppedTurnEndEntryId().toString());
              dto.setCancelledCommandCount(result.cancelledCommandCount());
              return dto;
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

  /**
   * 基于 runtime-spring Redis overlay 的 snapshot-first realtime SSE tail。
   *
   * <p>浏览器重连后 {@code Last-Event-ID} 覆盖 {@code afterRevision}。两者都是规范的十进制持久游标；Redis delta 事件故意不携带 SSE
   * id。Thread 是否存在通过 {@link HarnessRuntime#getThreadSnapshot} 校验。
   */
  @GetMapping(path = "/{threadId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable String threadId,
      @RequestParam(defaultValue = "0") String afterRevision,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
    withRuntimeTranslation(() -> runtime.getThreadSnapshot(id));
    long revision =
        withRuntimeTranslation(
            () -> parseRevision(lastEventId == null ? afterRevision : lastEventId));
    return StudioHarnessThreadSseEmitter.stream(
        id,
        revision,
        realtimeEventTail.initialCursor(id),
        realtimeEventTail,
        revisionHub,
        eventStreamExecutor);
  }

  static long parseRevision(String raw) {
    if (raw == null || !raw.matches("0|[1-9]\\d*")) {
      throw new IllegalArgumentException("afterRevision must be a non-negative decimal bigint");
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("afterRevision exceeds bigint range", error);
    }
  }

  /** 将 Harness Runtime 异常翻译为统一 HTTP 错误响应。 */
  private static <T> T withRuntimeTranslation(Supplier<T> operation) {
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
