package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.share.canvas.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas Resource 直读预签名 HTTP 边界：解析 Resource → blobId → blob 预签名， 绝不暴露 bucket 或对象 key。
 *
 * <p>上传/完成端点已移除：上传由全局 storage 服务负责，CREATE_RESOURCE_NODE 在服务端事务内消费 READY 上传。 TEXT 资源没有 blob 内容，请求其
 * URL 返回 400。控制器总是注册；S3 未启用（SystemSettings.storageMedia.s3Enabled=false）时请求确定性返回 503。
 */
@RestController
@RequestMapping("/api/canvases/{canvasId}/resources")
public class StudioCanvasResourceController {

  private final CanvasQueryService queryService;
  private final ObjectProvider<StorageBlobManager> blobManagers;

  public StudioCanvasResourceController(
      CanvasQueryService queryService, ObjectProvider<StorageBlobManager> blobManagers) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
  }

  @PostMapping("/{resourceId}/download-url")
  public Result<CanvasPresignedUrlDTO> originalUrl(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("resourceId") String resourceIdText) {
    return sign(canvasIdText, resourceIdText, true);
  }

  @PostMapping("/{resourceId}/preview-url")
  public Result<CanvasPresignedUrlDTO> previewUrl(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("resourceId") String resourceIdText) {
    return sign(canvasIdText, resourceIdText, false);
  }

  private Result<CanvasPresignedUrlDTO> sign(
      String canvasIdText, String resourceIdText, boolean original) {
    try {
      UUID canvasId = WebDtoMapper.parseUuid(canvasIdText, "canvasId");
      UUID resourceId = WebDtoMapper.parseUuid(resourceIdText, "resourceId");
      CanvasResource resource = findResource(canvasId, resourceId);
      if (resource.isText()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "TEXT resource has no blob content");
      }
      StorageBlobManager blobManager = requireBlobManager();
      return Results.ok(
          WebDtoMapper.toPresignedUrlDto(
              original
                  ? blobManager.presignOriginalUrl(resource.blobId())
                  : blobManager.presignPreviewUrl(resource.blobId())));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  private CanvasResource findResource(UUID canvasId, UUID resourceId) {
    Optional<CanvasSnapshot> snapshot = queryService.findSnapshot(canvasId);
    if (snapshot.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown canvas: " + canvasId);
    }
    return snapshot.get().nodes().stream()
        .flatMap(node -> node.resources().stream())
        .filter(resource -> resource.id().equals(resourceId))
        .findFirst()
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "unknown resource: " + resourceId));
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "global blob storage is unavailable");
    }
    return blobManager;
  }
}
