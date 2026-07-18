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

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;

import java.util.List;

/** Session tree 只读查询；创建与消息提交走 Thread API。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/sessions")
public class StudioHarnessSessionController {

  private final HarnessSessionQueryService queryService;

  @GetMapping
  public Result<List<HarnessSessionDTO>> listRootSessions() {
    return Results.ok(queryService.listRootSessions());
  }

  @GetMapping("/{id}")
  public Result<HarnessSessionDTO> getSession(@PathVariable("id") String id) {
    try {
      return Results.ok(queryService.getSession(id));
    } catch (IllegalArgumentException error) {
      throw translateMissingResource(error);
    }
  }

  @GetMapping("/{id}/entries")
  public Result<List<HarnessSessionEntryDTO>> listEntries(@PathVariable("id") String id) {
    try {
      return Results.ok(queryService.listEntries(id));
    } catch (IllegalArgumentException error) {
      throw translateMissingResource(error);
    }
  }

  private static RuntimeException translateMissingResource(IllegalArgumentException error) {
    String message = error.getMessage();
    if (message != null
        && (message.startsWith("unknown session:") || message.startsWith("session not found:"))) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
    }
    return error;
  }
}
