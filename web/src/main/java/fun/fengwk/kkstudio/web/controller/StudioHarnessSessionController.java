package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;

import java.util.List;
import java.util.function.Supplier;

/**
 * Session 创建与查询 API：Session 只组织 Entry Tree，不创建或持有 Thread。
 *
 * <p>统一返回 {@link Result}；创建类接口使用 {@link Results#created}（201），由 ResultResponseBodyAdvice 同步 HTTP
 * 状态。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/sessions")
public class StudioHarnessSessionController {

  private final HarnessSessionQueryService queryService;
  private final HarnessSessionCommandService commandService;

  @PostMapping
  public Result<HarnessSessionDTO> createSession(@RequestBody HarnessSessionCreateDTO request) {
    return Results.created(withHttpTranslation(() -> commandService.createSession(request)));
  }

  @GetMapping
  public Result<List<HarnessSessionDTO>> listRootSessions() {
    return Results.ok(queryService.listRootSessions());
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
