package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits;
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
import fun.fengwk.kkstudio.share.model.HarnessThreadToolsetSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Thread API：查询、typed mailbox 输入、路径 entries、inputs、events 与 SSE。 */
@RestController
@RequestMapping("/api")
public class StudioHarnessThreadController {
  private final HarnessThreadCommandService commandService;
  private final HarnessThreadQueryService queryService;
  private final HarnessObservabilityQueryService observabilityQueryService;

  public StudioHarnessThreadController(
      HarnessThreadCommandService commandService,
      HarnessThreadQueryService queryService,
      HarnessObservabilityQueryService observabilityQueryService) {
    this.commandService = Objects.requireNonNull(commandService, "commandService");
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.observabilityQueryService =
        Objects.requireNonNull(observabilityQueryService, "observabilityQueryService");
  }

  @GetMapping("/threads")
  public Result<List<HarnessThreadDTO>> listAllThreads() {
    return Results.ok(queryService.listAll());
  }

  @GetMapping("/threads/{threadId}")
  public Result<HarnessThreadDTO> getThread(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.getThread(threadId)));
  }

  @GetMapping("/sessions/{sessionId}/threads")
  public Result<List<HarnessThreadDTO>> listThreads(@PathVariable String sessionId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.listBySession(sessionId)));
  }

  @PostMapping("/threads/{threadId}/messages")
  public ResponseEntity<Result<HarnessThreadInputDTO>> submitMessage(
      @PathVariable String threadId, @RequestBody HarnessThreadMessageCreateDTO createDTO) {
    HarnessThreadInputDTO input =
        withMissingResourceTranslation(() -> commandService.submitUserMessage(threadId, createDTO));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Results.ok(input));
  }

  @PostMapping("/threads/{threadId}/messages/custom")
  public ResponseEntity<Result<HarnessThreadInputDTO>> submitCustomMessage(
      @PathVariable String threadId, @RequestBody HarnessThreadCustomMessageCreateDTO createDTO) {
    HarnessThreadInputDTO input =
        withMissingResourceTranslation(
            () -> commandService.submitCustomMessage(threadId, createDTO));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Results.ok(input));
  }

  @PutMapping("/threads/{threadId}/yolo")
  public ResponseEntity<Result<HarnessThreadInputDTO>> queueYolo(
      @PathVariable String threadId, @RequestBody HarnessThreadYoloSetDTO request) {
    HarnessThreadInputDTO input =
        withMissingResourceTranslation(() -> commandService.queueYolo(threadId, request));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Results.ok(input));
  }

  @PutMapping("/threads/{threadId}/agent")
  public ResponseEntity<Result<HarnessThreadInputDTO>> queueAgent(
      @PathVariable String threadId, @RequestBody HarnessThreadAgentSetDTO request) {
    HarnessThreadInputDTO input =
        withMissingResourceTranslation(() -> commandService.queueAgent(threadId, request));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Results.ok(input));
  }

  @PutMapping("/threads/{threadId}/model")
  public ResponseEntity<Result<HarnessThreadInputDTO>> queueModel(
      @PathVariable String threadId, @RequestBody HarnessThreadModelSetDTO request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            Results.ok(
                withMissingResourceTranslation(
                    () -> commandService.queueModel(threadId, request))));
  }

  @PutMapping("/threads/{threadId}/toolset")
  public ResponseEntity<Result<HarnessThreadInputDTO>> queueToolset(
      @PathVariable String threadId, @RequestBody HarnessThreadToolsetSetDTO request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            Results.ok(
                withMissingResourceTranslation(
                    () -> commandService.queueToolset(threadId, request))));
  }

  @PostMapping("/threads/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(withMissingResourceTranslation(() -> commandService.stop(threadId, request)));
  }

  @PostMapping("/threads/{threadId}/retry")
  public ResponseEntity<Result<HarnessThreadDTO>> retry(@PathVariable String threadId) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(Results.ok(withMissingResourceTranslation(() -> commandService.retry(threadId))));
  }

  @GetMapping("/threads/{threadId}/entries")
  public Result<List<HarnessSessionEntryDTO>> listPathEntries(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.listPathEntries(threadId)));
  }

  @GetMapping("/threads/{threadId}/inputs")
  public Result<List<HarnessThreadInputDTO>> listInputs(@PathVariable String threadId) {
    return Results.ok(withMissingResourceTranslation(() -> queryService.listInputs(threadId)));
  }

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

  @GetMapping(
      path = "/threads/{threadId}/events/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable String threadId,
      @RequestParam(defaultValue = "0") String afterEventId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    long cursor =
        ObservabilityLimits.resolveResumeCursor(afterEventId, lastEventId, "afterEventId");
    withMissingResourceTranslation(() -> queryService.getThread(threadId));
    return StudioHarnessThreadSseEmitter.stream(threadId, cursor, observabilityQueryService);
  }

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
