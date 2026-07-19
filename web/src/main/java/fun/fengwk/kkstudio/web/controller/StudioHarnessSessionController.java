package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;

import java.util.List;
import java.util.function.Supplier;

/** Session 创建、查询及 Session 内 branch Thread 创建 API。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/sessions")
public class StudioHarnessSessionController {

  private final HarnessSessionQueryService queryService;
  private final HarnessSessionCommandService commandService;
  private final HarnessThreadCommandService threadCommandService;

  @PostMapping
  public ResponseEntity<Result<HarnessSessionDTO>> createSession(
      @RequestBody HarnessSessionCreateDTO request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Results.ok(withHttpTranslation(() -> commandService.createSession(request))));
  }

  @GetMapping
  public Result<List<HarnessSessionDTO>> listRootSessions() {
    return Results.ok(queryService.listRootSessions());
  }

  @GetMapping("/{id}")
  public Result<HarnessSessionDTO> getSession(@PathVariable("id") String id) {
    try {
      return Results.ok(queryService.getSession(id));
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw translateHttpError(error);
    }
  }

  @GetMapping("/{id}/entries")
  public Result<List<HarnessSessionEntryDTO>> listEntries(@PathVariable("id") String id) {
    try {
      return Results.ok(queryService.listEntries(id));
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw translateHttpError(error);
    }
  }

  @PostMapping("/{id}/threads")
  public ResponseEntity<Result<HarnessThreadDTO>> createThread(
      @PathVariable("id") String id, @RequestBody HarnessThreadCreateDTO request) {
    try {
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(Results.ok(threadCommandService.createThread(id, request)));
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw translateHttpError(error);
    }
  }

  private static <T> T withHttpTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw translateHttpError(error);
    }
  }

  private static RuntimeException translateHttpError(RuntimeException error) {
    String message = error.getMessage();
    if (message != null
        && (message.startsWith("unknown session:")
            || message.startsWith("session not found:")
            || message.startsWith("unknown entry:")
            || message.startsWith("unknown agent definition:"))) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
    }
    if (error instanceof IllegalStateException) {
      return new ResponseStatusException(HttpStatus.CONFLICT, message, error);
    }
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message, error);
  }
}
