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
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.util.List;

/** 稳定 Environment Card 管理 REST API。 */
@AllArgsConstructor
@RequestMapping("/api/ai/environments")
@RestController
public class StudioEnvironmentController {

  private final EnvironmentService environmentService;

  @GetMapping
  public Result<List<EnvironmentCardDTO>> listEnvironments() {
    return Results.ok(environmentService.list());
  }

  @GetMapping("/{id}")
  public Result<EnvironmentCardDTO> getEnvironment(@PathVariable String id) {
    return Results.ok(environmentService.get(EnvironmentId.parse(id)));
  }

  @PostMapping
  public Result<EnvironmentCardDTO> createEnvironment(@RequestBody EnvironmentCreateDTO request) {
    return Results.ok(environmentService.create(request));
  }

  @PutMapping("/{id}")
  public Result<EnvironmentCardDTO> updateEnvironment(
      @PathVariable String id,
      @RequestParam String expectedVersion,
      @RequestBody EnvironmentUpdateDTO request) {
    return Results.ok(environmentService.update(EnvironmentId.parse(id), request, expectedVersion));
  }

  @PostMapping("/{id}/registration-token")
  public Result<EnvironmentCardDTO> rotateToken(
      @PathVariable String id, @RequestParam String expectedVersion) {
    return Results.ok(environmentService.rotateToken(EnvironmentId.parse(id), expectedVersion));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteEnvironment(
      @PathVariable String id, @RequestParam String expectedVersion) {
    environmentService.delete(EnvironmentId.parse(id), expectedVersion);
    return Results.ok();
  }
}
