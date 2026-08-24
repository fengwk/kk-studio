package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionService;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionModelDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunRequestDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canvas Function model catalog 与 run 生命周期 HTTP 边界。 */
@RestController
@RequestMapping("/api")
public class StudioCanvasFunctionController {

  private final CanvasFunctionCatalog catalog;
  private final CanvasFunctionService runtimeService;
  private final WebDtoMapper mapper;

  public StudioCanvasFunctionController(
      CanvasFunctionCatalog catalog, CanvasFunctionService runtimeService, WebDtoMapper mapper) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @GetMapping("/canvas-function-models")
  public Result<List<CanvasFunctionModelDTO>> listModels() {
    return Results.ok(
        catalog.list().stream()
            .map(
                registered ->
                    mapper.toDto(
                        registered.model(), registered.enabled(), registered.unavailableReason()))
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
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
      UUID nodeId = WebDtoMapper.parseUuid(nodeIdText, "nodeId");
      return Results.accepted(
          mapper.toDto(runtimeService.start(canvasId, nodeId, request.getRequestId())));
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
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
      UUID nodeId = WebDtoMapper.parseUuid(nodeIdText, "nodeId");
      return Results.ok(mapper.toDto(runtimeService.get(canvasId, nodeId)));
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
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
      UUID nodeId = WebDtoMapper.parseUuid(nodeIdText, "nodeId");
      return Results.ok(
          mapper.toDto(runtimeService.cancel(canvasId, nodeId, request.getRequestId())));
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
