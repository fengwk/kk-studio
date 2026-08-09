package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.studio.resource.CanvasPresignedUrl;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceStorageException;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceStorageService;
import fun.fengwk.kkstudio.core.studio.resource.CanvasUploadReservation;
import fun.fengwk.kkstudio.share.studio.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.share.studio.CanvasResourceDTO;
import fun.fengwk.kkstudio.share.studio.CanvasUploadReservationDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasUploadRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

/** Canvas upload/finalize 与 Resource 按需签名 HTTP 边界。 */
@ConditionalOnProperty(prefix = "kk-studio.storage.s3", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/canvases/{canvasId}")
@RequiredArgsConstructor
public class StudioCanvasResourceController {

  private final CanvasResourceStorageService storageService;

  @PostMapping("/uploads")
  public Result<CanvasUploadReservationDTO> reserve(
      @PathVariable("canvasId") String canvasIdText,
      @RequestBody CreateCanvasUploadRequestDTO request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
    }
    try {
      long canvasId = StudioWebMapper.parsePositiveId(canvasIdText, "canvasId");
      CanvasResourceKind kind = parseKind(request.getKind());
      long size = StudioWebMapper.parsePositiveId(request.getSize(), "size");
      return Results.created(
          toDto(
              storageService.reserve(
                  canvasId, kind, request.getFilename(), request.getMediaType(), size)));
    } catch (CanvasResourceStorageException e) {
      throw map(e);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  @PostMapping("/uploads/{uploadId}/complete")
  public Result<CanvasResourceDTO> complete(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("uploadId") String uploadIdText) {
    try {
      long canvasId = StudioWebMapper.parsePositiveId(canvasIdText, "canvasId");
      long uploadId = StudioWebMapper.parsePositiveId(uploadIdText, "uploadId");
      return Results.ok(StudioWebMapper.toDto(storageService.complete(canvasId, uploadId)));
    } catch (CanvasResourceStorageException e) {
      throw map(e);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  @PostMapping("/resources/{resourceId}/download-url")
  public Result<CanvasPresignedUrlDTO> originalUrl(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("resourceId") String resourceIdText) {
    try {
      long canvasId = StudioWebMapper.parsePositiveId(canvasIdText, "canvasId");
      long resourceId = StudioWebMapper.parsePositiveId(resourceIdText, "resourceId");
      return Results.ok(toDto(storageService.originalUrl(canvasId, resourceId)));
    } catch (CanvasResourceStorageException e) {
      throw map(e);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  @PostMapping("/resources/{resourceId}/preview-url")
  public Result<CanvasPresignedUrlDTO> previewUrl(
      @PathVariable("canvasId") String canvasIdText,
      @PathVariable("resourceId") String resourceIdText) {
    try {
      long canvasId = StudioWebMapper.parsePositiveId(canvasIdText, "canvasId");
      long resourceId = StudioWebMapper.parsePositiveId(resourceIdText, "resourceId");
      return Results.ok(toDto(storageService.previewUrl(canvasId, resourceId)));
    } catch (CanvasResourceStorageException e) {
      throw map(e);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private static CanvasResourceKind parseKind(String kind) {
    if (kind == null) {
      throw new IllegalArgumentException("kind is required");
    }
    try {
      CanvasResourceKind parsed = CanvasResourceKind.valueOf(kind);
      if (parsed == CanvasResourceKind.TEXT) {
        throw new IllegalArgumentException("kind must be IMAGE, VIDEO, or AUDIO");
      }
      return parsed;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("kind must be IMAGE, VIDEO, or AUDIO", e);
    }
  }

  private static CanvasUploadReservationDTO toDto(CanvasUploadReservation reservation) {
    CanvasUploadReservationDTO dto = new CanvasUploadReservationDTO();
    dto.setUploadId(Long.toString(reservation.uploadId()));
    dto.setMethod(reservation.method());
    dto.setUrl(reservation.url());
    dto.setHeaders(reservation.headers());
    dto.setExpiresAt(reservation.expiresAt());
    return dto;
  }

  private static CanvasPresignedUrlDTO toDto(CanvasPresignedUrl presigned) {
    CanvasPresignedUrlDTO dto = new CanvasPresignedUrlDTO();
    dto.setMethod(presigned.method());
    dto.setUrl(presigned.url());
    dto.setHeaders(presigned.headers());
    dto.setExpiresAt(presigned.expiresAt());
    return dto;
  }

  private static ResponseStatusException map(CanvasResourceStorageException error) {
    HttpStatus status =
        switch (error.reason()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case EXPIRED -> HttpStatus.GONE;
          case S3_MISSING -> HttpStatus.BAD_REQUEST;
        };
    return new ResponseStatusException(status, error.getMessage(), error);
  }
}
