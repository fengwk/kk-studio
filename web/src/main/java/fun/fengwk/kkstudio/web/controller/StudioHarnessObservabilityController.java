package fun.fengwk.kkstudio.web.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.observability.service.model.ArtifactContent;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;

/** Harness observability：Thread tool/model/interaction 投影和 artifact 下载。 */
@RestController
@RequestMapping("/api")
public class StudioHarnessObservabilityController {

  private final HarnessObservabilityQueryService observabilityService;

  public StudioHarnessObservabilityController(
      HarnessObservabilityQueryService observabilityService) {
    this.observabilityService = observabilityService;
  }

  @GetMapping("/threads/{id}/tool-invocations")
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

  @GetMapping("/threads/{id}/model-invocations")
  public Result<List<ModelInvocationDTO>> listModelInvocations(@PathVariable("id") String id) {
    try {
      return Results.ok(observabilityService.listModelInvocations(id));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping("/threads/{id}/interactions/open")
  public Result<List<InteractionDTO>> listOpenInteractions(@PathVariable("id") String id) {
    try {
      return Results.ok(observabilityService.listOpenInteractions(id));
    } catch (IllegalArgumentException error) {
      throw translate(error);
    }
  }

  @GetMapping("/artifacts/{id}")
  public ResponseEntity<ByteArrayResource> getArtifact(@PathVariable("id") String id) {
    ArtifactContent artifact;
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
        && (message.startsWith("unknown thread:")
            || message.startsWith("unknown session:")
            || message.startsWith("unknown tool invocation:")
            || message.startsWith("unknown artifact:"))) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
    }
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
  }
}
