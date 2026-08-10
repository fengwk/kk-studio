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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionModelRegistry;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionRuntimeService;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionModelDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionRunDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionRunRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;

/** Canvas Function model registry 与 run 生命周期 HTTP 边界。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StudioCanvasFunctionController {

  private final CanvasFunctionModelRegistry registry;
  private final CanvasFunctionRuntimeService runtimeService;

  @GetMapping("/canvas-function-models")
  public Result<List<CanvasFunctionModelDTO>> listModels() {
    return Results.ok(
        registry.list().stream()
            .map(
                registered ->
                    StudioWebMapper.toDto(
                        registered.model(),
                        registered.adapter().enabled(),
                        registered.adapter().unavailableReason()))
            .toList());
  }

  @PostMapping("/canvases/{canvasId}/nodes/{nodeId}/runs")
  public Result<CanvasFunctionRunDTO> start(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("nodeId") String nodeIdText,
      @RequestBody CanvasFunctionRunRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      return Results.accepted(
          StudioWebMapper.toDto(
              runtimeService.start(
                  StudioWebMapper.parsePositiveId(canvasIdText, "canvasId"),
                  StudioWebMapper.parsePositiveId(nodeIdText, "nodeId"),
                  request.getRequestId())));
    } catch (CanvasFunctionRunException exception) {
      throw map(exception);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  @GetMapping("/canvases/{canvasId}/nodes/{nodeId}/run")
  public Result<CanvasFunctionRunDTO> get(
      @PathVariable("canvasId") String canvasIdText, @PathVariable("nodeId") String nodeIdText) {
    try {
      return Results.ok(
          StudioWebMapper.toDto(
              runtimeService.get(
                  StudioWebMapper.parsePositiveId(canvasIdText, "canvasId"),
                  StudioWebMapper.parsePositiveId(nodeIdText, "nodeId"))));
    } catch (CanvasFunctionRunException exception) {
      throw map(exception);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  @PostMapping("/canvases/{canvasId}/nodes/{nodeId}/run/cancel")
  public Result<CanvasFunctionRunDTO> cancel(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("nodeId") String nodeIdText,
      @RequestBody CanvasFunctionRunRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      return Results.ok(
          StudioWebMapper.toDto(
              runtimeService.cancel(
                  StudioWebMapper.parsePositiveId(canvasIdText, "canvasId"),
                  StudioWebMapper.parsePositiveId(nodeIdText, "nodeId"),
                  request.getRequestId())));
    } catch (CanvasFunctionRunException exception) {
      throw map(exception);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  private static ResponseStatusException map(CanvasFunctionRunException exception) {
    HttpStatus status =
        switch (exception.reason()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return new ResponseStatusException(status, exception.getMessage(), exception);
  }
}
