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

import fun.fengwk.kkstudio.core.ai.runtime.realtime.HarnessRealtimeEventTail;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloSetDTO;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Thread API：快照查询、typed mailbox 输入与 Redis realtime SSE。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐；入队类接口使用 {@link Results#accepted}（202）。
 */
@RestController
@RequestMapping("/api")
public class StudioHarnessThreadController {
  private final HarnessThreadCommandService commandService;
  private final HarnessThreadQueryService queryService;
  private final HarnessRealtimeEventTail realtimeEventTail;
  private final ThreadRevisionSseHub revisionHub;
  private final Executor eventStreamExecutor;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessThreadCommandService commandService,
      HarnessThreadQueryService queryService,
      HarnessRealtimeEventTail realtimeEventTail,
      ThreadRevisionSseHub revisionHub,
      @Qualifier("harnessEventStreamTaskExecutor") Executor eventStreamExecutor) {
    this.commandService = Objects.requireNonNull(commandService, "commandService");
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.realtimeEventTail = Objects.requireNonNull(realtimeEventTail, "realtimeEventTail");
    this.revisionHub = Objects.requireNonNull(revisionHub, "revisionHub");
    this.eventStreamExecutor = Objects.requireNonNull(eventStreamExecutor, "eventStreamExecutor");
  }

  /** 查询所有 Thread。 */
  @GetMapping("/threads")
  public Result<List<HarnessThreadDTO>> listAllThreads() {
    return Results.ok(queryService.listAll());
  }

  /** 创建 UNBOUND Thread：无请求体，head 为空，尚未绑定任何 Session。 */
  @PostMapping("/threads")
  public Result<HarnessThreadDTO> createThread() {
    return Results.created(withMissingResourceTranslation(commandService::createThread));
  }

  /** 查询指定 Thread 的当前状态。 */
  @GetMapping("/threads/{threadId}")
  public Result<HarnessThreadDTO> getThread(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.getThread(threadId)));
  }

  /** One coherent PostgreSQL chat-runtime projection, identified by its durable revision cursor. */
  @GetMapping("/threads/{threadId}/snapshot")
  public Result<HarnessThreadSnapshotDTO> getSnapshot(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.getSnapshot(threadId)));
  }

  /** 绑定、跨 Session 切换或清空 Thread head，携带 expectedExecutionEpoch 做 CAS fencing。 */
  @PutMapping("/threads/{threadId}/head")
  public Result<HarnessThreadDTO> updateHead(
      @PathVariable String threadId, @RequestBody HarnessThreadHeadUpdateDTO request) {
    return Results.ok(
        withMissingResourceTranslation(() -> commandService.updateHead(threadId, request)));
  }

  /** 为 UNBOUND Thread 原子创建 Session/ROOT/RUNTIME_CONFIG 并绑定 head。 */
  @PostMapping("/threads/{threadId}/bootstrap")
  public Result<HarnessThreadBootstrapResultDTO> bootstrapThread(
      @PathVariable String threadId, @RequestBody HarnessThreadBootstrapDTO request) {
    return Results.created(
        withMissingResourceTranslation(() -> commandService.bootstrapThread(threadId, request)));
  }

  /** 将用户消息异步入队；202 仅表示消息已接受，不代表模型已完成。 */
  @PostMapping("/threads/{threadId}/messages")
  public Result<HarnessThreadInputDTO> submitMessage(
      @PathVariable String threadId, @RequestBody HarnessThreadMessageCreateDTO createDTO) {
    return Results.accepted(
        withMissingResourceTranslation(
            () -> commandService.submitUserMessage(threadId, createDTO)));
  }

  /** 将自定义消息异步入队。 */
  @PostMapping("/threads/{threadId}/messages/custom")
  public Result<HarnessThreadInputDTO> submitCustomMessage(
      @PathVariable String threadId, @RequestBody HarnessThreadCustomMessageCreateDTO createDTO) {
    return Results.accepted(
        withMissingResourceTranslation(
            () -> commandService.submitCustomMessage(threadId, createDTO)));
  }

  /** 异步更新 Thread 的 YOLO 运行策略。 */
  @PutMapping("/threads/{threadId}/yolo")
  public Result<HarnessThreadInputDTO> queueYolo(
      @PathVariable String threadId, @RequestBody HarnessThreadYoloSetDTO request) {
    return Results.accepted(
        withMissingResourceTranslation(() -> commandService.queueYolo(threadId, request)));
  }

  /** 异步切换 Thread 的当前 Agent。 */
  @PutMapping("/threads/{threadId}/agent")
  public Result<HarnessThreadInputDTO> queueAgent(
      @PathVariable String threadId, @RequestBody HarnessThreadAgentSetDTO request) {
    return Results.accepted(
        withMissingResourceTranslation(() -> commandService.queueAgent(threadId, request)));
  }

  /** 异步切换 Thread 的当前 Model 与 Variant。 */
  @PutMapping("/threads/{threadId}/model")
  public Result<HarnessThreadInputDTO> queueModel(
      @PathVariable String threadId, @RequestBody HarnessThreadModelSetDTO request) {
    return Results.accepted(
        withMissingResourceTranslation(() -> commandService.queueModel(threadId, request)));
  }

  /**
   * 原子停止 Thread：当前 head 仍有 response debt 时，安全的 text/thinking stream snapshot 持久化为 {@code
   * ASSISTANT_ABORTED}；快照不存在、为空或不安全时写入 {@code ASSISTANT_ERROR(CANCELLED)} barrier。随后递增
   * executionEpoch，并取消尚未处理的输入、可安全取消的 Invocation 与 OPEN Interaction。
   */
  @PostMapping("/threads/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(withMissingResourceTranslation(() -> commandService.stop(threadId, request)));
  }

  /**
   * Snapshot-first realtime SSE tail over Redis Streams.
   *
   * <p>{@code Last-Event-ID} overrides {@code afterRevision} after a browser reconnect. Both are
   * canonical decimal durable cursors; Redis delta events deliberately have no SSE id.
   */
  @GetMapping(
      path = "/threads/{threadId}/events/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable String threadId,
      @RequestParam(defaultValue = "0") String afterRevision,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    withMissingResourceTranslation(() -> queryService.getThread(threadId));
    long revision =
        withMissingResourceTranslation(
            () -> parseRevision(lastEventId == null ? afterRevision : lastEventId));
    long id = HarnessIds.parsePositive(threadId, "threadId");
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

  /** 将服务层异常转换为统一的 HTTP 错误响应。 */
  private static <T> T withMissingResourceTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      String message = error.getMessage();
      if (message != null
          && (message.startsWith("unknown thread:")
              || message.startsWith("unknown session:")
              || message.startsWith("unknown entry:")
              || message.startsWith("unknown agent definition:"))) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
      }
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message, error);
    }
  }
}
