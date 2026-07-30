package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.ai.environment.service.LiveEnvironmentQueryService;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;

import java.util.List;

/**
 * Read-only live Environment registry API.
 *
 * <p>Environments are server-memory only; there is no create/update/delete surface. Daemon
 * connections populate the registry through the WebSocket gateway. Mapping stays in Core so this
 * controller depends only on application DTOs.
 */
@AllArgsConstructor
@RequestMapping("/api/ai/environment")
@RestController
public class StudioToolEnvironmentController {

  private final LiveEnvironmentQueryService liveEnvironmentQueryService;

  @GetMapping
  public Result<List<LiveEnvironmentDTO>> listEnvironments() {
    return Results.ok(liveEnvironmentQueryService.listEnvironments());
  }
}
