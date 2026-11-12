package fun.fengwk.kkstudio.platform.harness.model;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.UUID;

/** Creates and reuses the durable model-sized image blob for a source image and tier. */
@Component
public final class ProviderImageModelViewService {

  private static final String IMAGE_VIEW_NAME_PREFIX = "model-view-";

  private final StorageBlobContentService contentService;
  private final StorageBlobManager blobManager;
  private final StorageBlobRepository blobRepository;
  private final StorageUploadService uploadService;
  private final TransactionTemplate transactionTemplate;
  private final long maxBlobBytes;

  public ProviderImageModelViewService(
      StorageBlobContentService contentService,
      StorageBlobManager blobManager,
      StorageBlobRepository blobRepository,
      StorageUploadService uploadService,
      PlatformTransactionManager transactionManager) {
    this.contentService = Objects.requireNonNull(contentService, "contentService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.maxBlobBytes = ProviderInlineBlobReader.Limits.DEFAULT.maxBlobBytes();
  }

  /**
   * Returns a direct original view when no scaling is needed, otherwise persists a unique
   * model-view blob.
   */
  public ModelView resolve(
      UUID sourceBlobId, String mediaType, long sizeBytes, ImageInputTier tier) {
    Objects.requireNonNull(sourceBlobId, "sourceBlobId");
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(tier, "tier");
    if (tier.isOriginal()) {
      return scaleOriginal(sourceBlobId, mediaType, sizeBytes, tier);
    }

    StorageBlobRepository.ImageModelView stored =
        blobRepository.getImageModelView(sourceBlobId, tier);
    if (stored != null) {
      return storedView(stored);
    }

    ProviderImageScaler.Scaled scaled = scaleSource(sourceBlobId, mediaType, sizeBytes, tier);
    if (!scaled.resized()) {
      return new ModelView(
          sourceBlobId,
          mediaType,
          sizeBytes,
          scaled.originalWidth(),
          scaled.originalHeight(),
          scaled.width(),
          scaled.height(),
          false);
    }

    String filename = filename(sourceBlobId, tier, scaled.mediaType());
    StorageUploadService.StagedUpload staged =
        uploadService.stage(
            filename, scaled.mediaType(), new ByteArrayInputStream(scaled.bytes()), maxBlobBytes);
    try {
      return transactionTemplate.execute(
          status -> registerOrReuse(sourceBlobId, tier, scaled, staged));
    } catch (RuntimeException | Error failure) {
      try {
        uploadService.delete(staged.uploadId());
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private ModelView scaleOriginal(
      UUID sourceBlobId, String mediaType, long sizeBytes, ImageInputTier tier) {
    ProviderImageScaler.Scaled scaled = scaleSource(sourceBlobId, mediaType, sizeBytes, tier);
    return new ModelView(
        sourceBlobId,
        mediaType,
        sizeBytes,
        scaled.originalWidth(),
        scaled.originalHeight(),
        scaled.width(),
        scaled.height(),
        scaled.resized());
  }

  private ProviderImageScaler.Scaled scaleSource(
      UUID sourceBlobId, String mediaType, long sizeBytes, ImageInputTier tier) {
    StorageBlobContent content = contentService.readBlobContent(sourceBlobId, maxBlobBytes);
    if (!sourceBlobId.equals(content.getBlobId())
        || !mediaType.equals(content.getMediaType())
        || sizeBytes != content.getSizeBytes()
        || content.getBytes().length != sizeBytes) {
      throw new IllegalStateException("image source facts changed while creating a model view");
    }
    return ProviderImageScaler.scale(mediaType, content.getBytes(), tier, maxBlobBytes);
  }

  private ModelView registerOrReuse(
      UUID sourceBlobId,
      ImageInputTier tier,
      ProviderImageScaler.Scaled scaled,
      StorageUploadService.StagedUpload staged) {
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(staged.uploadId());
    if (!ready.blobId().equals(staged.blobId())) {
      throw new IllegalStateException("staged image model view changed its blob identity");
    }
    StorageBlobRepository.ImageModelView candidate =
        new StorageBlobRepository.ImageModelView(
            sourceBlobId,
            tier,
            ready.blobId(),
            scaled.originalWidth(),
            scaled.originalHeight(),
            scaled.width(),
            scaled.height());
    boolean inserted = blobRepository.insertImageModelView(candidate);
    if (inserted) {
      blobManager.retain(ready.blobId());
    }
    uploadService.delete(staged.uploadId());
    StorageBlobRepository.ImageModelView stored =
        inserted ? candidate : blobRepository.getImageModelView(sourceBlobId, tier);
    if (stored == null) {
      throw new IllegalStateException("image model view registration lost its durable row");
    }
    return storedView(stored);
  }

  private ModelView storedView(StorageBlobRepository.ImageModelView stored) {
    StorageBlob viewBlob = blobManager.getBlob(stored.viewBlobId());
    if (viewBlob == null || viewBlob.getState() != StorageBlobState.ACTIVE) {
      throw new IllegalStateException("durable image model view references a non-active blob");
    }
    return new ModelView(
        stored.viewBlobId(),
        viewBlob.getMediaType(),
        viewBlob.getSizeBytes(),
        stored.originalWidth(),
        stored.originalHeight(),
        stored.displayWidth(),
        stored.displayHeight(),
        true);
  }

  private static String filename(UUID sourceBlobId, ImageInputTier tier, String mediaType) {
    String extension = "image/jpeg".equals(mediaType) ? ".jpg" : ".png";
    return IMAGE_VIEW_NAME_PREFIX + sourceBlobId + "-" + tier.wireName() + extension;
  }

  public record ModelView(
      UUID blobId,
      String mediaType,
      long sizeBytes,
      int originalWidth,
      int originalHeight,
      int displayWidth,
      int displayHeight,
      boolean resized) {}
}
