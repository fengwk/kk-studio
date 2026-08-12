package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.studio.realtime.CanvasRealtimeService;
import fun.fengwk.kkstudio.core.studio.thread.CanvasThreadService;
import fun.fengwk.kkstudio.core.studio.thread.CanvasThreadService.CanvasFirstSendCommand;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;
import fun.fengwk.kkstudio.share.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.studio.CanvasChangesDTO;
import fun.fengwk.kkstudio.share.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.studio.CanvasPatchDTO;
import fun.fengwk.kkstudio.share.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.studio.CanvasThreadBranchSettingsDTO;
import fun.fengwk.kkstudio.share.studio.CanvasThreadFirstSendRequestDTO;
import fun.fengwk.kkstudio.share.studio.CanvasThreadFirstSendResponseDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeWebMapper;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 持久化全局单实例产品的 Canvas HTTP 边界，统一返回 convention {@link Result}。
 *
 * <p>所有实体 id 都以 canonical UUID 字符串跨 HTTP 边界；graph 版本是 long（{@code canvas_document.version} 的
 * 公共坐标系）。SSE 端点返回 {@code text/event-stream}，事件只有 'version'（携带前进版本）与 'resync'（整体快照恢复）。
 */
@RestController
@RequestMapping("/api/canvases")
public class StudioCanvasController {

  private final CanvasQueryService canvasQueryService;
  private final CanvasCommandService canvasCommandService;
  private final CanvasRealtimeService realtimeService;
  private final CanvasThreadService canvasThreadService;
  private final CanvasVersionEventSource versionEventSource;
  private final Executor eventStreamExecutor;
  private final StudioWebMapper mapper;

  public StudioCanvasController(
      CanvasQueryService canvasQueryService,
      CanvasCommandService canvasCommandService,
      CanvasRealtimeService realtimeService,
      CanvasThreadService canvasThreadService,
      CanvasVersionEventSource versionEventSource,
      @Qualifier("harnessEventStreamTaskExecutor") Executor eventStreamExecutor,
      StudioWebMapper mapper) {
    this.canvasQueryService = Objects.requireNonNull(canvasQueryService, "canvasQueryService");
    this.canvasCommandService =
        Objects.requireNonNull(canvasCommandService, "canvasCommandService");
    this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService");
    this.canvasThreadService = Objects.requireNonNull(canvasThreadService, "canvasThreadService");
    this.versionEventSource = Objects.requireNonNull(versionEventSource, "versionEventSource");
    this.eventStreamExecutor = Objects.requireNonNull(eventStreamExecutor, "eventStreamExecutor");
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

  /**
   * Canvas graph version SSE：'version' 事件携带持久版本，'resync' 事件要求整体快照恢复。 重连后 {@code Last-Event-ID} 覆盖
   * {@code afterVersion}，两者都是规范的非负十进制版本。
   */
  @GetMapping(path = "/{canvasId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable("canvasId") String canvasIdText,
      @RequestParam(defaultValue = "0") String afterVersion,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      long version = parseVersion(lastEventId == null ? afterVersion : lastEventId, "afterVersion");
      return CanvasSseEmitter.stream(canvasId, version, versionEventSource, eventStreamExecutor);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping("/{canvasId}/thread/messages")
  public Result<CanvasThreadFirstSendResponseDTO> sendFirstMessage(
      @PathVariable("canvasId") String canvasIdText,
      @RequestBody CanvasThreadFirstSendRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      UUID canvasId = StudioWebMapper.parseUuid(canvasIdText, "canvasId");
      CanvasThreadService.CanvasFirstSendResult result =
          canvasThreadService.sendFirstMessage(canvasId, toFirstSendCommand(request));
      CanvasThreadFirstSendResponseDTO response = new CanvasThreadFirstSendResponseDTO();
      response.setThreadId(result.threadId().toString());
      response.setDocument(mapper.toDto(result.document()));
      return Results.created(response);
    } catch (HarnessRuntimeConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.reason().name(), ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  private static CanvasFirstSendCommand toFirstSendCommand(
      CanvasThreadFirstSendRequestDTO request) {
    String commandId = StudioWebMapper.parseUuid(request.getCommandId(), "commandId").toString();
    return new CanvasFirstSendCommand(
        commandId,
        toBranchSettings(request.getBranchSettings()),
        Boolean.TRUE.equals(request.getYoloEnabled()),
        toUserMessageContents(request.getContents()));
  }

  private static BranchSettings toBranchSettings(CanvasThreadBranchSettingsDTO settings) {
    if (settings == null) {
      throw new IllegalArgumentException("branchSettings is required");
    }
    if (settings.getModel() == null) {
      throw new IllegalArgumentException("branchSettings.model is required");
    }
    return new BranchSettings(
        new EnvironmentName(settings.getEnvironmentName()),
        settings.getAgentName(),
        new ModelSelection(
            settings.getModel().getProviderName(),
            settings.getModel().getModelName(),
            settings.getModel().getVariant()),
        settings.getActiveTools());
  }

  private static List<AgentMessageContent> toUserMessageContents(
      List<HarnessUserMessageContentDTO> contents) {
    return HarnessRuntimeWebMapper.toUserMessageContents(contents);
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
