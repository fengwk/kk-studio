package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.harness.tool.service.ToolInvocationDecisionService;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDecisionDTO;

/** Global Tool permission decision API。Thread YOLO 走 Thread API。 */
@AllArgsConstructor
@RequestMapping("/api")
@RestController
public class StudioToolInvocationController {
  private final ToolInvocationDecisionService decisionService;

  @PostMapping("/tool-invocations/{invocationId}/decision")
  public Result<ToolInvocationDTO> decide(
      @PathVariable long invocationId, @RequestBody ToolInvocationDecisionDTO request) {
    try {
      return Results.ok(decisionService.decide(invocationId, request));
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }
}
