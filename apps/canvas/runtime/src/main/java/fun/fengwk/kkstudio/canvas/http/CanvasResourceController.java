package fun.fengwk.kkstudio.canvas.http;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.canvas.dto.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.canvas.infra.dto.CanvasDtoMapper;
import fun.fengwk.kkstudio.canvas.infra.platform.CanvasResourceUrlService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;

import java.util.Objects;
import java.util.UUID;

/**
 * Canvas Resource 直读预签名 HTTP 边界：解析 Resource → blobId → blob 预签名， 绝不暴露 bucket 或对象 key。
 *
 * <p>只有被当前 Canvas 持有的 Blob 才能签发 URL。
 */
@RestController
@RequestMapping("/api/canvas/canvases/{canvasId}/blobs")
public class CanvasResourceController {

  private final CanvasResourceUrlService resourceUrlService;

  public CanvasResourceController(CanvasResourceUrlService resourceUrlService) {
    this.resourceUrlService = Objects.requireNonNull(resourceUrlService, "resourceUrlService");
  }

  @PostMapping("/{blobId}/download-url")
  public CanvasPresignedUrlDTO originalUrl(
      @PathVariable UUID canvasId, @PathVariable UUID blobId, StudioPrincipal principal) {
    return sign(principal, canvasId, blobId, true);
  }

  @PostMapping("/{blobId}/preview-url")
  public CanvasPresignedUrlDTO previewUrl(
      @PathVariable UUID canvasId, @PathVariable UUID blobId, StudioPrincipal principal) {
    return sign(principal, canvasId, blobId, false);
  }

  private CanvasPresignedUrlDTO sign(
      StudioPrincipal principal, UUID canvasId, UUID blobId, boolean original) {
    return CanvasDtoMapper.toPresignedUrlDto(
        resourceUrlService.presign(principal, canvasId, blobId, original));
  }
}
