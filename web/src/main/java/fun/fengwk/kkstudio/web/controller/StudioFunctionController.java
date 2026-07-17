package fun.fengwk.kkstudio.web.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * providers exist.
 */
@RestController
@RequestMapping("/api/functions")
@RequiredArgsConstructor
public class StudioFunctionController {

  private final FunctionCatalog functionCatalog;
  private final FunctionRuntimeService functionRuntimeService;

  @GetMapping
  public List<FunctionDefinitionDTO> list(@RequestParam("workspaceId") long workspaceId) {
    return functionCatalog.listVisible(workspaceId).stream()
        .map(StudioWebMapper::toDto)
        .collect(Collectors.toList());
  }

  @GetMapping("/{functionId}/versions/{version}")
  public ResponseEntity<FunctionDefinitionDTO> get(
      @PathVariable("functionId") String functionId, @PathVariable("version") String version) {
    return functionCatalog
        .find(new FunctionRef(functionId, version))
        .map(StudioWebMapper::toDto)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/runs")
  public ResponseEntity<?> submit(@RequestBody SubmitFunctionRunRequestDTO request) {
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
      FunctionRunDTO dto = StudioWebMapper.toDto(functionRuntimeService.submit(executionRequest));
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(dto);
    } catch (StudioFeatureNotReadyException ex) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ex.getMessage());
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(ex.getMessage());
    }
  }

  @GetMapping("/runs/{runId}")
  public ResponseEntity<FunctionRunDTO> getRun(@PathVariable("runId") long runId) {
    return functionRuntimeService
        .findRun(runId)
        .map(StudioWebMapper::toDto)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static Long parseNullableLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Long.parseLong(value);
  }
}
