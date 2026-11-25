package fun.fengwk.kkstudio.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Authenticated preview/download for resources held by the requested Session. */
@RestController
@RequestMapping("/api/harness/sessions")
public class HarnessResourceController {

  private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

  private final SessionBlobRefManager refManager;
  private final StorageBlobRepository blobRepository;
  private final StorageBlobContentService contentService;
  private final StorageBlobManager blobManager;
  private final ResourceAccess resourceAccess;

  public HarnessResourceController(
      SessionBlobRefManager refManager,
      StorageBlobRepository blobRepository,
      StorageBlobContentService contentService,
      StorageBlobManager blobManager,
      ResourceAccess resourceAccess) {
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.contentService = Objects.requireNonNull(contentService, "contentService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.resourceAccess = Objects.requireNonNull(resourceAccess, "resourceAccess");
  }

  @PostMapping("/{sessionId}/blobs/{blobId}/download-url")
  public StoragePresignedUrlDTO downloadUrl(
      @PathVariable UUID sessionId, @PathVariable UUID blobId, StudioPrincipal principal) {
    return presign(principal, sessionId, blobId, true);
  }

  @PostMapping("/{sessionId}/blobs/{blobId}/preview-url")
  public StoragePresignedUrlDTO previewUrl(
      @PathVariable UUID sessionId, @PathVariable UUID blobId, StudioPrincipal principal) {
    return presign(principal, sessionId, blobId, false);
  }

  private StoragePresignedUrlDTO presign(
      StudioPrincipal principal, UUID sessionId, UUID blobId, boolean original) {
    resourceAccess.require(principal, Kind.SESSION, sessionId);
    if (!refManager.contains(sessionId, blobId)) {
      throw ResourceAccess.notFound();
    }
    return original
        ? blobManager.presignOriginalUrl(blobId)
        : blobManager.presignPreviewUrl(blobId);
  }

  /** A session-held resource is streamed inline for preview or as an attachment for download. */
  @GetMapping(value = "/{sessionId}/resources/{resourceId}")
  public ResponseEntity<StreamingResponseBody> getResource(
      @PathVariable UUID sessionId,
      @PathVariable UUID resourceId,
      @RequestParam(defaultValue = "false") boolean download,
      StudioPrincipal principal) {
    resourceAccess.require(principal, Kind.SESSION, sessionId);
    if (!refManager.contains(sessionId, resourceId)) {
      throw DomainException.notFound("resource");
    }
    StorageBlob blob = blobRepository.getById(resourceId);
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      throw DomainException.notFound("resource");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(contentType(blob.getMediaType()));
    headers.setContentDisposition(
        (download ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename("resource", StandardCharsets.UTF_8)
            .build());
    headers.setCacheControl("private, no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Content-Security-Policy", "default-src 'none'; sandbox");

    StreamingResponseBody body =
        output ->
            contentService.withBlobStream(
                resourceId,
                ReadDeadline.after(READ_TIMEOUT),
                input -> {
                  try {
                    input.transferTo(output);
                    return null;
                  } catch (IOException error) {
                    throw new UncheckedIOException(error);
                  }
                });
    return new ResponseEntity<>(body, headers, HttpStatus.OK);
  }

  private static MediaType contentType(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    try {
      return MediaType.parseMediaType(mediaType);
    } catch (InvalidMediaTypeException ignored) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
