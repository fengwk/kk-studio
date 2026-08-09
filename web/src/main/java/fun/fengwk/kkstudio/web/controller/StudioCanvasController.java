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
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 持久化全局单实例产品的 Canvas HTTP 边界，统一返回 convention {@link Result}。
 *
 * <p>所有 bigint id/revision 都以严格的十进制字符串跨 HTTP 边界。
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
  public Result<CanvasSnapshotDTO> get(@PathVariable("canvasId") String canvasIdText) {
    try {
      long canvasId = StudioWebMapper.parsePositiveId(canvasIdText, "canvasId");
      return canvasQueryService
          .findSnapshot(canvasId)
          .map(snapshot -> Results.ok(StudioWebMapper.toDto(snapshot)))
          .orElseThrow(
              () ->
                  new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown canvas: " + canvasId));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
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
      @PathVariable("canvasId") String canvasIdText,
      @RequestBody ApplyCanvasCommandsRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      long canvasId = StudioWebMapper.parsePositiveId(canvasIdText, "canvasId");
      long expectedRevision =
          StudioWebMapper.parseNonNegativeLong(request.getExpectedRevision(), "expectedRevision");
      return Results.ok(
          StudioWebMapper.toDto(
              canvasCommandService.applyCommands(
                  canvasId,
                  expectedRevision,
                  request.getCommandId(),
                  StudioWebMapper.toCommands(request.getCommands()))));
    } catch (CanvasConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.reason().name(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
