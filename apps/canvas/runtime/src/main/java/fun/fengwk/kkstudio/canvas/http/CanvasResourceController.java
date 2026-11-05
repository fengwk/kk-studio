package fun.fengwk.kkstudio.canvas.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.canvas.dto.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.canvas.infra.dto.CanvasDtoMapper;
import fun.fengwk.kkstudio.canvas.infra.platform.CanvasResourceUrlService;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.http.ApiResponse;

import java.util.Objects;
import java.util.UUID;

/**
 * Canvas Resource 直读预签名 HTTP 边界：解析 Resource → blobId → blob 预签名， 绝不暴露 bucket 或对象 key。
 *
 * <p>TEXT 资源没有 blob 内容，请求其 URL 返回 400。
 */
@RestController
@RequestMapping("/api/canvases/{canvasId}/resources")
public class CanvasResourceController {

  private final CanvasResourceUrlService resourceUrlService;

  public CanvasResourceController(CanvasResourceUrlService resourceUrlService) {
    this.resourceUrlService = Objects.requireNonNull(resourceUrlService, "resourceUrlService");
  }

  @PostMapping("/{resourceId}/download-url")
  public ApiResponse<CanvasPresignedUrlDTO> originalUrl(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("resourceId") String resourceIdText) {
    return sign(canvasIdText, resourceIdText, true);
  }

  @PostMapping("/{resourceId}/preview-url")
  public ApiResponse<CanvasPresignedUrlDTO> previewUrl(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("resourceId") String resourceIdText) {
    return sign(canvasIdText, resourceIdText, false);
  }

  private ApiResponse<CanvasPresignedUrlDTO> sign(
      String canvasIdText, String resourceIdText, boolean original) {
    try {
      UUID canvasId = Uuids.parseCanonical(canvasIdText, "canvasId");
      UUID resourceId = Uuids.parseCanonical(resourceIdText, "resourceId");
      return ApiResponse.ok(
          CanvasDtoMapper.toPresignedUrlDto(
              resourceUrlService.presign(canvasId, resourceId, original)));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
