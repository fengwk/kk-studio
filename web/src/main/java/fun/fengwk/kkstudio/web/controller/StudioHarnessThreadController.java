package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
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

import fun.fengwk.kkstudio.core.harness.redis.RedisRealtimeEventTail;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread API：查询、typed mailbox 输入、路径 entries、inputs、events 与 SSE。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐；入队类接口使用 {@link Results#accepted}（202）。
 */
@RestController
@RequestMapping("/api")
public class StudioHarnessThreadController {
  private final HarnessThreadCommandService commandService;
  private final HarnessThreadQueryService queryService;
  private final RedisRealtimeEventTail realtimeEventTail;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessThreadCommandService commandService,
      HarnessThreadQueryService queryService,
      RedisRealtimeEventTail realtimeEventTail) {
    this.commandService = Objects.requireNonNull(commandService, "commandService");
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.realtimeEventTail = Objects.requireNonNull(realtimeEventTail, "realtimeEventTail");
  }

  /** 查询所有 Thread。 */
  @GetMapping("/threads")
  public Result<List<HarnessThreadDTO>> listAllThreads() {
    return Results.ok(queryService.listAll());
  }

  /** 查询指定 Thread 的当前状态。 */
  @GetMapping("/threads/{threadId}")
  public Result<HarnessThreadDTO> getThread(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.getThread(threadId)));
  }

  /** 查询指定 Session 下的所有 Thread。 */
  @GetMapping("/sessions/{sessionId}/threads")
  public Result<List<HarnessThreadDTO>> listThreads(@PathVariable String sessionId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.listBySession(sessionId)));
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

  /** 停止 Thread，并取消尚未处理的输入。 */
  @PostMapping("/threads/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(withMissingResourceTranslation(() -> commandService.stop(threadId, request)));
  }

  /** 查询当前 branch path 上的持久化 Session Entry。 */
  @GetMapping("/threads/{threadId}/entries")
  public Result<List<HarnessSessionEntryDTO>> listPathEntries(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.listPathEntries(threadId)));
  }

  /** 查询 Thread mailbox 中的输入及其处理状态。 */
  @GetMapping("/threads/{threadId}/inputs")
  public Result<List<HarnessThreadInputDTO>> listInputs(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.listInputs(threadId)));
  }

  /** 从指定事件 ID 后分页查询 Thread Event。 */
  @GetMapping("/threads/{threadId}/events")
  public Result<List<ThreadEventDTO>> listEvents(
      @PathVariable String threadId,
      @RequestParam(defaultValue = "0") long afterEventId,
      @RequestParam(required = false) Integer limit) {
    int page = limit == null ? 100 : limit;
    return Results.ok(
        withMissingResourceTranslation(
            () -> queryService.listEvents(threadId, afterEventId, page)));
  }

  /**
   * Snapshot-first realtime SSE tail over Redis Streams.
   *
   * <p>{@code afterEventId} / {@code Last-Event-ID} are Redis stream ids ({@code ms-seq}). Clients
   * must load the PostgreSQL snapshot before connecting; Redis loss only drops the lossy tail.
   */
  @GetMapping(
      path = "/threads/{threadId}/events/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable String threadId,
      @RequestParam(defaultValue = "0-0") String afterEventId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    withMissingResourceTranslation(() -> queryService.getThread(threadId));
    String cursor =
        lastEventId != null && !lastEventId.isBlank() ? lastEventId.trim() : afterEventId;
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return StudioHarnessThreadSseEmitter.stream(id, cursor, realtimeEventTail);
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
