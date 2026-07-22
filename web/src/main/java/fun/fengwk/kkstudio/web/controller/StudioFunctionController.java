package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.share.model.studio.FunctionDefinitionDTO;
import fun.fengwk.kkstudio.share.model.studio.FunctionRunDTO;
import fun.fengwk.kkstudio.share.model.studio.SubmitFunctionRunRequestDTO;
import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.model.FunctionRef;
import fun.fengwk.kkstudio.studio.runtime.FunctionCatalog;
import fun.fengwk.kkstudio.studio.runtime.FunctionExecutionRequest;
import fun.fengwk.kkstudio.studio.runtime.FunctionRuntimeService;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Function catalog and runtime HTTP boundary.
 *
 * <p>Catalog listing works for seeded system Functions. Submit remains stubbed until generation
 * providers exist. Returns convention {@link Result}.
 */
@RestController
@RequestMapping("/api/functions")
@RequiredArgsConstructor
public class StudioFunctionController {

  private final FunctionCatalog functionCatalog;
  private final FunctionRuntimeService functionRuntimeService;

  @GetMapping
  public Result<List<FunctionDefinitionDTO>> list(@RequestParam("workspaceId") long workspaceId) {
    return Results.ok(
        functionCatalog.listVisible(workspaceId).stream()
            .map(StudioWebMapper::toDto)
            .collect(Collectors.toList()));
  }

  @GetMapping("/{functionId}/versions/{version}")
  public Result<FunctionDefinitionDTO> get(
      @PathVariable("functionId") String functionId, @PathVariable("version") String version) {
    return functionCatalog
        .find(new FunctionRef(functionId, version))
        .map(definition -> Results.ok(StudioWebMapper.toDto(definition)))
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "unknown function: " + functionId + "@" + version));
  }

  @PostMapping("/runs")
  public Result<FunctionRunDTO> submit(@RequestBody SubmitFunctionRunRequestDTO request) {
    try {
      FunctionExecutionRequest executionRequest =
          new FunctionExecutionRequest(
              Long.parseLong(request.getWorkspaceId()),
              parseNullableLong(request.getCanvasId()),
              parseNullableLong(request.getFunctionNodeId()),
              new FunctionRef(request.getFunctionId(), request.getFunctionVersion()),
              request.getConfigSnapshotJson() == null ? "{}" : request.getConfigSnapshotJson(),
              parseNullableLong(request.getConfigRevision()),
              request.getIdempotencyKey());
      return Results.accepted(
          StudioWebMapper.toDto(functionRuntimeService.submit(executionRequest)));
    } catch (StudioFeatureNotReadyException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/runs/{runId}")
  public Result<FunctionRunDTO> getRun(@PathVariable("runId") long runId) {
    return functionRuntimeService
        .findRun(runId)
        .map(run -> Results.ok(StudioWebMapper.toDto(run)))
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "unknown function run: " + runId));
  }

  private static Long parseNullableLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Long.parseLong(value);
  }
}
