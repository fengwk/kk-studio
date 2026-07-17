package fun.fengwk.kkstudio.web.controller;

import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.normalizeLimit;
import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.requireNonNegativeCursor;
import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.resolveResumeCursor;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;

/**
 * T15 harness observability endpoints. Strict 400 vs 404 semantics are enforced by inspecting the
 * originating {@link IllegalArgumentException} message; the legacy /api/agent/** surface remains
 * untouched.
 */
@RestController
@RequestMapping("/api")
public class StudioHarnessObservabilityController {

  private final HarnessObservabilityQueryService observabilityService;
  private final StudioHarnessObservabilitySseEmitter sseEmitter;

  public StudioHarnessObservabilityController(
      HarnessObservabilityQueryService observabilityService,
      StudioHarnessObservabilitySseEmitter sseEmitter) {
    this.observabilityService = observabilityService;
    this.sseEmitter = sseEmitter;
  }

  @GetMapping("/runs/{id}/events")
  public Result<List<RunEventDTO>> listRunEvents(
      @PathVariable("id") String id,
      @RequestParam(value = "afterSequence", required = false) Long afterSequence,
      @RequestParam(value = "limit", required = false) Integer limit) {
    long cursor = afterSequence == null ? 0L : afterSequence;
    requireNonNegativeCursor(cursor, "afterSequence");
    int bounded = normalizeLimit(limit, ObservabilityLimits.DEFAULT_LIMIT);
    try {
      return Results.ok(observabilityService.listRunEvents(id, cursor, bounded));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping(value = "/runs/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamRunEvents(
      @PathVariable("id") String id,
      @RequestParam(value = "afterSequence", required = false) String afterSequence,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @RequestParam(value = "idleTimeoutMillis", required = false) Long idleTimeoutMillis) {
    try {
      long cursor = resolveResumeCursor(afterSequence, lastEventId, "afterSequence");
      return sseEmitter.openRunStream(id, cursor, idleTimeoutMillis);
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping("/sessions/{id}/activities")
  public Result<List<RootActivityDTO>> listRootActivities(
      @PathVariable("id") String id,
      @RequestParam(value = "afterEventId", required = false) Long afterEventId,
      @RequestParam(value = "limit", required = false) Integer limit) {
    long cursor = afterEventId == null ? 0L : afterEventId;
    requireNonNegativeCursor(cursor, "afterEventId");
    int bounded = normalizeLimit(limit, ObservabilityLimits.DEFAULT_LIMIT);
    try {
      return Results.ok(observabilityService.listRootActivities(id, cursor, bounded));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping(
      value = "/sessions/{id}/activities/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamRootActivities(
      @PathVariable("id") String id,
      @RequestParam(value = "afterEventId", required = false) String afterEventId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @RequestParam(value = "idleTimeoutMillis", required = false) Long idleTimeoutMillis) {
    try {
      long cursor = resolveResumeCursor(afterEventId, lastEventId, "afterEventId");
      return sseEmitter.openRootActivityStream(id, cursor, idleTimeoutMillis);
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping("/runs/{id}/tool-invocations")
  public Result<List<ToolInvocationDTO>> listToolInvocations(@PathVariable("id") String id) {
    try {
      return Results.ok(observabilityService.listToolInvocations(id));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping("/tool-invocations/{id}")
  public Result<ToolInvocationDTO> getToolInvocation(@PathVariable("id") String id) {
    try {
      return Results.ok(observabilityService.getToolInvocation(id));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping("/sessions/{id}/tasks")
  public Result<List<SubagentTaskDTO>> listSessionTasks(@PathVariable("id") String id) {
    try {
      return Results.ok(observabilityService.listSessionTasks(id));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  /**
   * Artifact GET returns persisted bytes with the original valid {@code mediaType}. Response
   * headers prevent MIME sniffing and sandbox document artifacts before a browser renders them.
   * Blank or non-positive identifiers translate to 400; unknown ids translate to 404. There is
   * intentionally no JSON / base64 fallback so consumers can pipe the response directly to file
   * outputs.
   */
  @GetMapping("/artifacts/{id}")
  public ResponseEntity<ByteArrayResource> getArtifact(@PathVariable("id") String id) {
    Artifact artifact;
    try {
      artifact = observabilityService.getArtifact(id);
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(safeMediaType(artifact.mediaType()));
    headers.setContentLength(artifact.sizeBytes());
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Content-Security-Policy", "sandbox");
    return new ResponseEntity<>(new ByteArrayResource(artifact.content()), headers, HttpStatus.OK);
  }

  private static MediaType safeMediaType(String mediaType) {
    try {
      return MediaType.parseMediaType(mediaType);
    } catch (InvalidMediaTypeException ignored) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  private static RuntimeException translate(IllegalArgumentException error) {
    String message = error.getMessage();
    if (message != null
        && (message.startsWith("unknown run:")
            || message.startsWith("unknown session:")
            || message.startsWith("unknown tool invocation:")
            || message.startsWith("unknown artifact:"))) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
    }
    // All other validation failures (malformed ids, negative cursors, out-of-range limits) are
    // request errors → 400.
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
  }
}
