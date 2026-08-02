package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.ai.runtime.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;

import java.util.List;
import java.util.function.Supplier;

/**
 * Session read-only API. Sessions are only created as a side effect of creating a Thread, so there
 * is no POST endpoint. All methods return a {@link Result} envelope; lookup misses are mapped to
 * 404 by the HTTP translation helper.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/ai/runtime/sessions")
public class StudioHarnessSessionController {

  private final HarnessSessionQueryService queryService;

  @GetMapping
  public Result<List<HarnessSessionDTO>> listSessions() {
    return Results.ok(queryService.listSessions());
  }

  @GetMapping("/{id}")
  public Result<HarnessSessionDTO> getSession(@PathVariable("id") String id) {
    return Results.ok(withHttpTranslation(() -> queryService.getSession(id)));
  }

  @GetMapping("/{id}/entries")
  public Result<List<HarnessSessionEntryDTO>> listEntries(@PathVariable("id") String id) {
    return Results.ok(withHttpTranslation(() -> queryService.listEntries(id)));
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
