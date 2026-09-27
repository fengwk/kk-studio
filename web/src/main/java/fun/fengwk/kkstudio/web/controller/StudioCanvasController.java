package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.share.canvas.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasPatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.canvas.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 产品的持久化 HTTP 边界，统一返回 convention {@link Result}。
 *
 * <p>Canvas 不拥有 Session：工作上下文属于 Harness 的 Thread/Invocation，本控制器只提供画布列表、快照与 typed command。 所有实体 id
 * 都以 canonical UUID 字符串跨 HTTP 边界；{@code revision} 是 long（{@code canvas_document.revision} 的公共坐标系）在
 * wire 上的规范十进制字符串。写入使用 typed command 批：前置条件是各语义组的编辑起点旧值，而不是整图版本，因此请求不携带
 * expectedVersion。实时事件经事件通道（{@code /api/events/v1}）订阅，本控制器不重复实现事件流。
 */
@RestController
@RequestMapping("/api/canvases")
public class StudioCanvasController {

  private final CanvasQueryService canvasQueryService;
  private final CanvasCommandService canvasCommandService;
  private final WebDtoMapper mapper;

  public StudioCanvasController(
      CanvasQueryService canvasQueryService,
      CanvasCommandService canvasCommandService,
      WebDtoMapper mapper) {
    this.canvasQueryService = Objects.requireNonNull(canvasQueryService, "canvasQueryService");
    this.canvasCommandService =
        Objects.requireNonNull(canvasCommandService, "canvasCommandService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @GetMapping
  public Result<List<CanvasDocumentDTO>> list() {
    return Results.ok(canvasQueryService.listDocuments().stream().map(mapper::toDto).toList());
  }

  @PostMapping
  public Result<CanvasDocumentDTO> create(
      @RequestBody(required = false) CreateCanvasRequestDTO request) {
    String title = request == null ? null : request.getTitle();
    if (title == null || title.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
    }
    try {
      return Results.created(mapper.toDto(canvasCommandService.createCanvas(title)));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/{canvasId}")
  public Result<CanvasSnapshotDTO> get(@PathVariable("canvasId") String canvasIdText) {
    try {
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
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

  /**
   * 应用一个 typed command 批。
   *
   * <p>接受时返回该批的接受位置与本次前进的变化集；同一幂等键的精确重放只返回当时记录的接受位置与空变化集，不重新执行命令。 语义组前置条件过期时整批不写入，返回 409
   * 与全部受影响对象及服务端权威值，供客户端合并、另存或放弃草稿。
   */
  @PostMapping("/{canvasId}/commands")
  public ResponseEntity<Result<CanvasPatchDTO>> applyCommands(
      @PathVariable("canvasId") String canvasIdText,
      @RequestBody(required = false) ApplyCanvasCommandsRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
      UUID idempotencyKey = WebDtoMapper.parseUuid(request.getIdempotencyKey(), "idempotencyKey");
      CanvasCommandResult result =
          canvasCommandService.applyCommands(
              canvasId, idempotencyKey, mapper.toCommands(request.getCommands()));
      return switch (result) {
        case CanvasCommandResult.Accepted accepted -> ResponseEntity.ok(
            Results.ok(mapper.toDto(accepted.patch())));
        case CanvasCommandResult.Conflicted conflicted -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(conflict(mapper.toDto(conflicted.conflicts())));
      };
    } catch (CanvasConflictException ex) {
      HttpStatus status =
          ex.reason() == CanvasConflictException.Reason.CANVAS_NOT_FOUND
              ? HttpStatus.NOT_FOUND
              : HttpStatus.CONFLICT;
      throw new ResponseStatusException(status, ex.reason().name(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @DeleteMapping("/{canvasId}")
  public Result<Void> delete(@PathVariable("canvasId") String canvasIdText) {
    try {
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
      canvasCommandService.deleteCanvas(canvasId);
      return Results.noContent();
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** 冲突响应只透出受影响对象、语义组与服务端权威值，不携带新的编辑基线。 */
  private static Result<CanvasPatchDTO> conflict(List<CanvasConflictDTO> conflicts) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", "Canvas command conflict");
    errorContext.put("detail", "command prerequisites are stale; no write was applied");
    errorContext.put("conflicts", conflicts);
    ImmutableResolvedConventionErrorCode errorCode =
        new ImmutableResolvedConventionErrorCode(
            HttpStatus.CONFLICT.value(),
            "CANVAS_COMMAND_CONFLICT",
            "Canvas command conflict",
            errorContext);
    return Results.error(errorCode);
  }
}
