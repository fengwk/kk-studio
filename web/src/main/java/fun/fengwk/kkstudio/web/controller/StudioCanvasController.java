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

import fun.fengwk.kkstudio.share.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Canvas HTTP boundary for the durable global single-instance product. Returns convention {@link
 * Result}.
 *
 * <p>The HTTP body is intentionally narrow: list / get / create (title only) / commands (revision +
 * commandId + commandsJson). {@code requestHash} is computed server-side from {@code commandsJson}.
 */
@RestController
@RequestMapping("/api/canvases")
@RequiredArgsConstructor
public class StudioCanvasController {

  private final CanvasQueryService canvasQueryService;
  private final CanvasCommandService canvasCommandService;

  @GetMapping
  public Result<List<CanvasDocumentDTO>> list() {
    return Results.ok(
        canvasQueryService.listDocuments().stream()
            .map(StudioWebMapper::toDto)
            .collect(Collectors.toList()));
  }

  @GetMapping("/{canvasId}")
  public Result<CanvasSnapshotDTO> get(@PathVariable("canvasId") long canvasId) {
    return canvasQueryService
        .findSnapshot(canvasId)
        .map(snapshot -> Results.ok(StudioWebMapper.toDto(snapshot)))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown canvas: " + canvasId));
  }

  @PostMapping
  public Result<CanvasDocumentDTO> create(
      @RequestBody(required = false) CreateCanvasRequestDTO request) {
    try {
      String title = request == null ? null : request.getTitle();
      return Results.created(StudioWebMapper.toDto(canvasCommandService.createCanvas(title)));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping("/{canvasId}/commands")
  public Result<CanvasSnapshotDTO> applyCommands(
      @PathVariable("canvasId") long canvasId, @RequestBody ApplyCanvasCommandsRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    long baseRevision;
    try {
      baseRevision = Long.parseLong(request.getBaseRevision());
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "baseRevision must be a non-negative integer", ex);
    }
    try {
      return Results.ok(
          StudioWebMapper.toDto(
              canvasCommandService.applyCommands(
                  canvasId, baseRevision, request.getCommandId(), request.getCommandsJson())));
    } catch (IllegalStateException ex) {
      if ("REVISION_CONFLICT".equals(ex.getMessage())
          || "IDEMPOTENCY_CONFLICT".equals(ex.getMessage())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
      }
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
