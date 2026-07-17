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

import fun.fengwk.kkstudio.share.model.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.model.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.model.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.model.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Canvas HTTP boundary.
 *
 * <p>Routes are registered now; durable command/query adapters still throw {@link
 * StudioFeatureNotReadyException}.
 */
@RestController
@RequestMapping("/api/canvases")
@RequiredArgsConstructor
public class StudioCanvasController {

  private final CanvasQueryService canvasQueryService;
  private final CanvasCommandService canvasCommandService;

  @GetMapping
  public List<CanvasDocumentDTO> list(@RequestParam("workspaceId") long workspaceId) {
    return canvasQueryService.listDocuments(workspaceId).stream()
        .map(StudioWebMapper::toDto)
        .collect(Collectors.toList());
  }

  @GetMapping("/{canvasId}")
  public ResponseEntity<CanvasSnapshotDTO> get(@PathVariable("canvasId") long canvasId) {
    return canvasQueryService
        .findSnapshot(canvasId)
        .map(StudioWebMapper::toDto)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestBody CreateCanvasRequestDTO request) {
    try {
      long workspaceId = Long.parseLong(request.getWorkspaceId());
      CanvasDocumentDTO dto =
          StudioWebMapper.toDto(canvasCommandService.createCanvas(workspaceId, request.getTitle()));
      return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    } catch (StudioFeatureNotReadyException ex) {
      return notImplemented(ex);
    }
  }

  @PostMapping("/{canvasId}/commands")
  public ResponseEntity<?> applyCommands(
      @PathVariable("canvasId") long canvasId, @RequestBody ApplyCanvasCommandsRequestDTO request) {
    try {
      CanvasSnapshotDTO dto =
          StudioWebMapper.toDto(
              canvasCommandService.applyCommands(
                  canvasId,
                  Long.parseLong(request.getBaseRevision()),
                  request.getCommandId(),
                  request.getRequestHash(),
                  request.getCommandsJson()));
      return ResponseEntity.ok(dto);
    } catch (StudioFeatureNotReadyException ex) {
      return notImplemented(ex);
    }
  }

  private static ResponseEntity<String> notImplemented(StudioFeatureNotReadyException ex) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ex.getMessage());
  }
}
