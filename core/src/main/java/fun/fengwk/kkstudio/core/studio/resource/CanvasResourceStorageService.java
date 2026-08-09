package fun.fengwk.kkstudio.core.studio.resource;

import org.springframework.util.MimeTypeUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourcePaths;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasUpload;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Canvas Resource reserve/finalize、按需签名与 provider 物化应用服务。 */
public class CanvasResourceStorageService implements CanvasResourceMaterializer {

  private static final int MAX_NAME_LENGTH = 256;
  private static final int MAX_MEDIA_TYPE_LENGTH = 256;
  private static final Set<String> IMAGE_EXTENSIONS =
      Set.of("jpg", "jpeg", "png", "webp", "heic", "heif");
  private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov");
  private static final Set<String> AUDIO_EXTENSIONS = Set.of("wav", "mp3");

  private final CanvasDocumentMapper documentMapper;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasUploadRepository uploadRepository;
  private final PostgresqlSequenceIdGenerator idGenerator;
  private final S3StorageService storageService;
  private final S3PresignService presignService;
  private final CanvasMediaProcessor mediaProcessor;
  private final CanvasResourceCommitter committer;
  private final CanvasMediaProperties properties;
  private final Supplier<Instant> now;

  public CanvasResourceStorageService(
      CanvasDocumentMapper documentMapper,
      CanvasResourceRepository resourceRepository,
      CanvasUploadRepository uploadRepository,
      PostgresqlSequenceIdGenerator idGenerator,
      S3StorageService storageService,
      S3PresignService presignService,
      CanvasMediaProcessor mediaProcessor,
      CanvasResourceCommitter committer,
      CanvasMediaProperties properties,
      Supplier<Instant> now) {
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.uploadRepository = Objects.requireNonNull(uploadRepository, "uploadRepository");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.storageService = Objects.requireNonNull(storageService, "storageService");
    this.presignService = Objects.requireNonNull(presignService, "presignService");
    this.mediaProcessor = Objects.requireNonNull(mediaProcessor, "mediaProcessor");
    this.committer = Objects.requireNonNull(committer, "committer");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.now = Objects.requireNonNull(now, "now");
    validateProperties(properties);
  }

  public CanvasUploadReservation reserve(
      long canvasId,
      CanvasResourceKind kind,
      String filename,
      String declaredMediaType,
      long declaredSize) {
    requireCanvas(canvasId);
    requireUploadKind(kind);
    String normalizedFilename = normalizeName(filename);
    validateExtension(kind, normalizedFilename);
    String normalizedMediaType = normalizeMediaType(declaredMediaType);
    validateSize(kind, declaredSize);

    long uploadId = idGenerator.next();
    Instant createdAt = now.get();
    Instant expiresAt = createdAt.plus(properties.getUploadExpiry());
    S3PresignedResponseDTO presigned =
        presignService.presignUpload(
            CanvasResourcePaths.original(canvasId, uploadId),
            normalizedMediaType,
            properties.getUploadExpiry().toSeconds());
    uploadRepository.add(
        new CanvasUpload(
            uploadId,
            canvasId,
            kind,
            normalizedFilename,
            normalizedMediaType,
            declaredSize,
            expiresAt,
            createdAt));
    return new CanvasUploadReservation(
        uploadId,
        presigned.getMethod(),
        presigned.getUrl(),
        copyHeaders(presigned),
        presigned.getExpiresAt());
  }

  public CanvasResource complete(long canvasId, long uploadId) {
    requirePositive(canvasId, "canvasId");
    requirePositive(uploadId, "uploadId");
    CanvasResource existing = resourceRepository.findById(canvasId, uploadId).orElse(null);
    if (existing != null) {
      return existing;
    }
    CanvasUpload upload =
        uploadRepository
            .findById(canvasId, uploadId)
            .orElseThrow(
                () ->
                    new CanvasResourceStorageException(
                        CanvasResourceStorageException.Reason.NOT_FOUND,
                        "Canvas upload not found"));
    if (!upload.expiresAt().isAfter(now.get())) {
      throw new CanvasResourceStorageException(
          CanvasResourceStorageException.Reason.EXPIRED, "Canvas upload expired");
    }

    String originalKey = CanvasResourcePaths.original(canvasId, uploadId);
    S3ObjectMetadata head = headRequired(originalKey);
    if (head.contentLength() != upload.declaredSize()) {
      throw new IllegalArgumentException(
          "S3 object size mismatch: declared "
              + upload.declaredSize()
              + " but HEAD returned "
              + head.contentLength());
    }
    try (S3ObjectStream object = readRequired(originalKey);
        CanvasProcessedMedia media =
            mediaProcessor.process(upload.kind(), object.inputStream(), head.contentLength())) {
      if (object.metadata().contentLength() != head.contentLength()) {
        throw new IllegalArgumentException("S3 GET metadata size does not match HEAD");
      }
      uploadPreview(canvasId, uploadId, upload.kind(), media);
      CanvasResource candidate =
          new CanvasResource(
              uploadId,
              canvasId,
              upload.kind(),
              media.mediaType(),
              upload.filename(),
              media.size(),
              null,
              media.metadataJson(),
              now.get());
      return committer.commitUpload(upload, candidate);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to close S3 object stream", e);
    }
  }

  public CanvasPresignedUrl originalUrl(long canvasId, long resourceId) {
    CanvasResource resource = requireResource(canvasId, resourceId);
    if (resource.kind() == CanvasResourceKind.TEXT) {
      throw new IllegalArgumentException("TEXT Resource has no original object");
    }
    return toCanvasUrl(
        presignService.presignDownload(CanvasResourcePaths.original(canvasId, resourceId), null));
  }

  public CanvasPresignedUrl previewUrl(long canvasId, long resourceId) {
    CanvasResource resource = requireResource(canvasId, resourceId);
    if (resource.kind() != CanvasResourceKind.IMAGE
        && resource.kind() != CanvasResourceKind.VIDEO) {
      throw new IllegalArgumentException(resource.kind() + " Resource has no preview");
    }
    return toCanvasUrl(
        presignService.presignDownload(CanvasResourcePaths.preview(canvasId, resourceId), null));
  }

  @Override
  public CanvasResource materialize(
      long canvasId,
      long resourceId,
      CanvasResourceKind kind,
      String name,
      String mediaType,
      long size,
      InputStream content) {
    requirePositive(canvasId, "canvasId");
    requirePositive(resourceId, "resourceId");
    Objects.requireNonNull(content, "content");
    CanvasResource existing = existingByGlobalId(canvasId, resourceId);
    if (existing != null) {
      return existing;
    }
    requireCanvas(canvasId);
    requireUploadKind(kind);
    String normalizedName = normalizeName(name);
    normalizeMediaType(mediaType);
    validateSize(kind, size);

    try (CanvasProcessedMedia media = mediaProcessor.process(kind, content, size);
        InputStream original = Files.newInputStream(media.originalPath())) {
      storageService.putObject(
          CanvasResourcePaths.original(canvasId, resourceId),
          original,
          media.size(),
          media.mediaType());
      uploadPreview(canvasId, resourceId, kind, media);
      CanvasResource candidate =
          new CanvasResource(
              resourceId,
              canvasId,
              kind,
              media.mediaType(),
              normalizedName,
              media.size(),
              null,
              media.metadataJson(),
              now.get());
      return committer.commitMaterialized(candidate);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to stream materialized Canvas Resource", e);
    }
  }

  private void uploadPreview(
      long canvasId, long resourceId, CanvasResourceKind kind, CanvasProcessedMedia media) {
    if (kind == CanvasResourceKind.AUDIO) {
      if (media.previewPath() != null) {
        throw new IllegalStateException("AUDIO processor unexpectedly produced preview");
      }
      return;
    }
    if (media.previewPath() == null) {
      throw new IllegalStateException(kind + " processor did not produce preview");
    }
    try (InputStream preview = Files.newInputStream(media.previewPath())) {
      storageService.putObject(
          CanvasResourcePaths.preview(canvasId, resourceId),
          preview,
          Files.size(media.previewPath()),
          "image/webp");
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to upload Canvas Resource preview", e);
    }
  }

  private CanvasResource requireResource(long canvasId, long resourceId) {
    requirePositive(canvasId, "canvasId");
    requirePositive(resourceId, "resourceId");
    return resourceRepository
        .findById(canvasId, resourceId)
        .orElseThrow(
            () ->
                new CanvasResourceStorageException(
                    CanvasResourceStorageException.Reason.NOT_FOUND, "Canvas Resource not found"));
  }

  private CanvasResource existingByGlobalId(long canvasId, long resourceId) {
    return resourceRepository
        .findById(resourceId)
        .map(
            resource -> {
              if (resource.canvasId() != canvasId) {
                throw new IllegalArgumentException("resourceId already belongs to another canvas");
              }
              return resource;
            })
        .orElse(null);
  }

  private S3ObjectMetadata headRequired(String key) {
    try {
      return storageService.headObject(key);
    } catch (NoSuchKeyException e) {
      throw missingObject();
    } catch (AwsServiceException e) {
      if (e.statusCode() == 404) {
        throw missingObject();
      }
      throw e;
    }
  }

  private S3ObjectStream readRequired(String key) {
    try {
      return storageService.readObject(key);
    } catch (NoSuchKeyException e) {
      throw missingObject();
    } catch (AwsServiceException e) {
      if (e.statusCode() == 404) {
        throw missingObject();
      }
      throw e;
    }
  }

  private CanvasResourceStorageException missingObject() {
    return new CanvasResourceStorageException(
        CanvasResourceStorageException.Reason.S3_MISSING,
        "Canvas upload original object is missing");
  }

  private void requireCanvas(long canvasId) {
    requirePositive(canvasId, "canvasId");
    if (documentMapper.getById(canvasId) == null) {
      throw new CanvasResourceStorageException(
          CanvasResourceStorageException.Reason.NOT_FOUND, "Canvas not found");
    }
  }

  private static void requireUploadKind(CanvasResourceKind kind) {
    Objects.requireNonNull(kind, "kind");
    if (kind == CanvasResourceKind.TEXT) {
      throw new IllegalArgumentException("TEXT cannot be uploaded");
    }
  }

  private static void validateSize(CanvasResourceKind kind, long size) {
    long max =
        switch (kind) {
          case IMAGE -> FfmpegCanvasMediaProcessor.MAX_IMAGE_SIZE;
          case VIDEO -> FfmpegCanvasMediaProcessor.MAX_VIDEO_SIZE;
          case AUDIO -> FfmpegCanvasMediaProcessor.MAX_AUDIO_SIZE;
          case TEXT -> throw new IllegalArgumentException("TEXT cannot be uploaded");
        };
    if (size <= 0L || size > max) {
      throw new IllegalArgumentException(kind + " size must be between 1 and " + max + " bytes");
    }
  }

  private static void validateExtension(CanvasResourceKind kind, String filename) {
    int separator = filename.lastIndexOf('.');
    String extension =
        separator < 0 ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    Set<String> allowed =
        switch (kind) {
          case IMAGE -> IMAGE_EXTENSIONS;
          case VIDEO -> VIDEO_EXTENSIONS;
          case AUDIO -> AUDIO_EXTENSIONS;
          case TEXT -> throw new IllegalArgumentException("TEXT cannot be uploaded");
        };
    if (!allowed.contains(extension)) {
      throw new IllegalArgumentException("unsupported " + kind + " filename extension");
    }
  }

  private static String normalizeName(String value) {
    if (value == null) {
      throw new IllegalArgumentException("filename/name must not be blank");
    }
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("filename/name must not be blank");
    }
    if (normalized.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "filename/name must be at most " + MAX_NAME_LENGTH + " characters");
    }
    for (int i = 0; i < normalized.length(); i++) {
      if (Character.isISOControl(normalized.charAt(i))) {
        throw new IllegalArgumentException("filename/name must not contain control characters");
      }
    }
    return normalized;
  }

  private static String normalizeMediaType(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    String normalized = mediaType.strip();
    if (normalized.length() > MAX_MEDIA_TYPE_LENGTH) {
      throw new IllegalArgumentException(
          "mediaType must be at most " + MAX_MEDIA_TYPE_LENGTH + " characters");
    }
    try {
      return MimeTypeUtils.parseMimeType(normalized).toString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("mediaType must be valid", e);
    }
  }

  private static CanvasPresignedUrl toCanvasUrl(S3PresignedResponseDTO presigned) {
    return new CanvasPresignedUrl(
        presigned.getMethod(),
        presigned.getUrl(),
        copyHeaders(presigned),
        presigned.getExpiresAt());
  }

  private static Map<String, String> copyHeaders(S3PresignedResponseDTO presigned) {
    return presigned.getHeaders() == null ? Map.of() : Map.copyOf(presigned.getHeaders());
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) {
      throw new IllegalArgumentException(field + " must be > 0");
    }
  }

  private static void validateProperties(CanvasMediaProperties properties) {
    Duration expiry = properties.getUploadExpiry();
    if (expiry == null || expiry.isZero() || expiry.isNegative()) {
      throw new IllegalArgumentException("uploadExpiry must be positive");
    }
    if (expiry.toSeconds() <= 0L) {
      throw new IllegalArgumentException("uploadExpiry must be at least one second");
    }
  }
}
