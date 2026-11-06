package fun.fengwk.kkstudio.canvas.http;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.canvas.dto.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.canvas.infra.dto.CanvasDtoMapper;
import fun.fengwk.kkstudio.canvas.infra.platform.CanvasResourceUrlService;

import java.util.Objects;
import java.util.UUID;

/**
 * Canvas Resource 直读预签名 HTTP 边界：解析 Resource → blobId → blob 预签名， 绝不暴露 bucket 或对象 key。
 *
 * <p>TEXT 资源没有 blob 内容，请求其 URL 返回 400。
 */
@RestController
@RequestMapping("/api/canvas/canvases/{canvasId}/resources")
public class CanvasResourceController {

  private final CanvasResourceUrlService resourceUrlService;

  public CanvasResourceController(CanvasResourceUrlService resourceUrlService) {
    this.resourceUrlService = Objects.requireNonNull(resourceUrlService, "resourceUrlService");
  }

  @PostMapping("/{resourceId}/download-url")
  public CanvasPresignedUrlDTO originalUrl(
      @PathVariable UUID canvasId, @PathVariable UUID resourceId) {
    return sign(canvasId, resourceId, true);
  }

  @PostMapping("/{resourceId}/preview-url")
  public CanvasPresignedUrlDTO previewUrl(
      @PathVariable UUID canvasId, @PathVariable UUID resourceId) {
    return sign(canvasId, resourceId, false);
  }

  private CanvasPresignedUrlDTO sign(UUID canvasId, UUID resourceId, boolean original) {
    return CanvasDtoMapper.toPresignedUrlDto(
        resourceUrlService.presign(canvasId, resourceId, original));
  }
}
