package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRotateTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.util.List;

/** 稳定 Environment Card 管理 REST API。 */
@AllArgsConstructor
@RequestMapping("/api/harness/environments")
@RestController
public class StudioEnvironmentController {

  private final EnvironmentService environmentService;

  @GetMapping
  public Result<List<EnvironmentCardDTO>> listEnvironments() {
    return Results.ok(environmentService.list());
  }

  @GetMapping("/{environmentId}")
  public Result<EnvironmentCardDTO> getEnvironment(@PathVariable String environmentId) {
    return Results.ok(environmentService.get(EnvironmentId.parse(environmentId)));
  }

  @PostMapping
  public Result<EnvironmentCardDTO> createEnvironment(@RequestBody EnvironmentCreateDTO request) {
    return Results.created(environmentService.create(request));
  }

  @PutMapping("/{environmentId}")
  public Result<EnvironmentCardDTO> updateEnvironment(
      @PathVariable String environmentId, @RequestBody EnvironmentUpdateDTO request) {
    String expectedVersion = request != null ? request.getExpectedVersion() : null;
    return Results.ok(
        environmentService.update(EnvironmentId.parse(environmentId), request, expectedVersion));
  }

  @PostMapping("/{environmentId}/registration-token")
  public Result<EnvironmentCardDTO> rotateToken(
      @PathVariable String environmentId, @RequestBody EnvironmentRotateTokenDTO request) {
    String expectedVersion = request != null ? request.getExpectedVersion() : null;
    return Results.ok(
        environmentService.rotateToken(EnvironmentId.parse(environmentId), expectedVersion));
  }

  @DeleteMapping("/{environmentId}")
  public Result<Void> deleteEnvironment(
      @PathVariable String environmentId, @RequestParam String expectedVersion) {
    environmentService.delete(EnvironmentId.parse(environmentId), expectedVersion);
    return Results.noContent();
  }
}
