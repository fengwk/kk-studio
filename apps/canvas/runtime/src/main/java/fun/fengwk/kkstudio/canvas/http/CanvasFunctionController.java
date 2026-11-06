package fun.fengwk.kkstudio.canvas.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionCancelRequestDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionDefinitionDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunRequestDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionUnknownResolutionDTO;
import fun.fengwk.kkstudio.canvas.infra.dto.CanvasDtoMapper;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionService;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas Function 目录、run 生命周期与 UNKNOWN 人工核查的 HTTP 边界。
 *
 * <p>目录只报告已安装函数；未安装插件留下的节点配置、引用与输出仍可从 Snapshot 读取，但不会被本目录声明，也无法启动新的执行。
 */
@RestController
@RequestMapping("/api/canvas")
public class CanvasFunctionController {

  private final PluginCanvasFunctionCatalogSource catalogSource;
  private final CanvasFunctionService runtimeService;
  private final CanvasDtoMapper mapper;

  public CanvasFunctionController(
      PluginCanvasFunctionCatalogSource catalogSource,
      CanvasFunctionService runtimeService,
      CanvasDtoMapper mapper) {
    this.catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
    this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @GetMapping("/functions")
  public List<CanvasFunctionDefinitionDTO> listFunctions() {
    try (PluginCanvasFunctionCatalogSource.Lease lease = catalogSource.acquire()) {
      return lease.catalog().list().stream()
          .map(
              registered ->
                  mapper.toDto(
                      registered.function(), registered.enabled(), registered.unavailableReason()))
          .toList();
    }
  }

  @PostMapping("/canvases/{canvasId}/nodes/{nodeId}/function-run")
  public ResponseEntity<CanvasFunctionRunDTO> start(
      @PathVariable UUID canvasId,
      @PathVariable UUID nodeId,
      @RequestBody CanvasFunctionRunRequestDTO request) {
    CanvasFunctionRunDTO accepted =
        mapper.toDto(
            runtimeService.start(
                canvasId,
                nodeId,
                request.getRequestId(),
                CanvasDtoMapper.parseVersion(request.getExpectedVersion(), "expectedVersion")));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
  }

  @GetMapping("/canvases/{canvasId}/nodes/{nodeId}/function-run")
  public CanvasFunctionRunDTO get(@PathVariable UUID canvasId, @PathVariable UUID nodeId) {
    return mapper.toDto(runtimeService.get(canvasId, nodeId));
  }

  @PostMapping("/canvases/{canvasId}/nodes/{nodeId}/function-run/cancel")
  public CanvasFunctionRunDTO cancel(
      @PathVariable UUID canvasId,
      @PathVariable UUID nodeId,
      @RequestBody CanvasFunctionCancelRequestDTO request) {
    return mapper.toDto(runtimeService.cancel(canvasId, nodeId, request.getRequestId()));
  }

  @PostMapping("/canvases/{canvasId}/nodes/{nodeId}/function-run/resolve")
  public CanvasFunctionRunDTO resolve(
      @PathVariable UUID canvasId,
      @PathVariable UUID nodeId,
      @RequestBody CanvasFunctionUnknownResolutionDTO request) {
    return mapper.toDto(
        runtimeService.resolve(
            canvasId,
            nodeId,
            request.getRequestId(),
            request.getResolution(),
            request.getVerification()));
  }
}
