package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.studio.StudioHarnessQueryService;
import fun.fengwk.kkstudio.core.studio.realtime.CanvasRealtimeService;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.studio.CanvasChangesDTO;
import fun.fengwk.kkstudio.share.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.studio.CanvasPatchDTO;
import fun.fengwk.kkstudio.share.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 持久化全局单实例产品的 Canvas HTTP 边界，统一返回 convention {@link Result}。
 *
 * <p>所有实体 id 都以 canonical UUID 字符串跨 HTTP 边界；graph 版本是 long（{@code canvas_document.version} 的
 * 公共坐标系）。实时事件经事件通道（{@code /api/events/v1}）订阅，本控制器只提供 snapshot/changes/命令 HTTP。
 */
@RestController
@RequestMapping("/api/canvases")
public class StudioCanvasController {

  private final CanvasQueryService canvasQueryService;
  private final CanvasCommandService canvasCommandService;
  private final CanvasRealtimeService realtimeService;
  private final StudioHarnessQueryService harnessQueryService;
  private final StudioWebMapper mapper;

  public StudioCanvasController(
      CanvasQueryService canvasQueryService,
      CanvasCommandService canvasCommandService,
      CanvasRealtimeService realtimeService,
      StudioHarnessQueryService harnessQueryService,
      StudioWebMapper mapper) {
    this.canvasQueryService = Objects.requireNonNull(canvasQueryService, "canvasQueryService");
    this.canvasCommandService =
        Objects.requireNonNull(canvasCommandService, "canvasCommandService");
    this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService");
    this.harnessQueryService = Objects.requireNonNull(harnessQueryService, "harnessQueryService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @GetMapping
  public Result<List<CanvasDocumentDTO>> list() {
    return Results.ok(canvasQueryService.listDocuments().stream().map(mapper::toDto).toList());
  }

  @PostMapping
  public Result<CanvasDocumentDTO> create(
      @RequestBody(required = false) CreateCanvasRequestDTO request) {
    try {
      String title = request == null ? null : request.getTitle();
      return Results.created(mapper.toDto(canvasCommandService.createCanvas(title)));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/{canvasId}")
  public Result<CanvasSnapshotDTO> get(@PathVariable("canvasId") String canvasIdText) {
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      return canvasQueryService
          .findSnapshot(canvasId)
          .map(snapshot -> Results.ok(mapper.toDto(snapshot)))
          .orElseThrow(
              () ->
                  new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown canvas: " + canvasId));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** 返回该 Canvas 关联的 Session 摘要（按归属时间从新到旧）。 */
  @GetMapping("/{canvasId}/sessions")
  public Result<List<HarnessSessionSummaryDTO>> sessions(
      @PathVariable("canvasId") String canvasIdText) {
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      return Results.ok(
          withRuntimeTranslation(() -> harnessQueryService.listCanvasSessions(canvasId)));
    } catch (AiResourceNotFoundException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** 将 Session owner 查询遇到的 Runtime 异常翻译为统一 HTTP 错误响应。 */
  private static <T> T withRuntimeTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (HarnessRuntimeNotFoundException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (HarnessRuntimeConflictException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }

  @PostMapping("/{canvasId}/commands")
  public Result<CanvasPatchDTO> applyCommands(
      @PathVariable("canvasId") String canvasIdText,
      @RequestBody ApplyCanvasCommandsRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      UUID commandId = StudioWebMapper.parseUuid(request.getCommandId(), "commandId");
      long expectedVersion = parseVersion(request.getExpectedVersion(), "expectedVersion");
      return Results.ok(
          mapper.toDto(
              canvasCommandService.applyCommands(
                  canvasId, expectedVersion, commandId, mapper.toCommands(request.getCommands()))));
    } catch (CanvasConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.reason().name(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @DeleteMapping("/{canvasId}")
  public Result<Void> delete(@PathVariable("canvasId") String canvasIdText) {
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      canvasCommandService.deleteCanvas(canvasId);
      return Results.noContent();
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/{canvasId}/changes")
  public Result<CanvasChangesDTO> changes(
      @PathVariable("canvasId") String canvasIdText,
      @RequestParam(defaultValue = "0") String afterVersion) {
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      long version = parseVersion(afterVersion, "afterVersion");
      return Results.ok(mapper.toDto(realtimeService.readChanges(canvasId, version)));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  static long parseVersion(String raw, String name) {
    if (raw == null || !raw.matches("0|[1-9]\\d*")) {
      throw new IllegalArgumentException(name + " must be a non-negative decimal");
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(name + " exceeds long range", error);
    }
  }
}
