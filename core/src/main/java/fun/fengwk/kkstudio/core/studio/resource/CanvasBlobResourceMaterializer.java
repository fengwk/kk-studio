package fun.fengwk.kkstudio.core.studio.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.persistence.postgresql.mapper.StorageBlobMapper;
import fun.fengwk.kkstudio.core.storage.persistence.postgresql.model.StorageBlobDO;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.core.storage.service.model.StorageMediaFacts;
import fun.fengwk.kkstudio.core.studio.repo.impl.PostgresqlCanvasResourceRepository;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasFunctionResourcePinMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 全局 blob 输出物化器：内容 spool 到临时文件 → 写入 {@code blobs/{blobId}/original} → ffprobe 事实 → 事务内 （blob 去重行 +
 * 资源行 insertIfAbsent）→ 提交后 best-effort 预览。相同 resourceId 幂等返回既有资源；并发竞争时失败方 释放自己刚创建的 blob 引用并返回胜者的资源。
 */
@Slf4j
public class CanvasBlobResourceMaterializer implements CanvasResourceMaterializer {

  private static final long MAX_MATERIALIZE_SIZE = 512L * 1024 * 1024;
  private static final String OCTET_STREAM = "application/octet-stream";

  private final S3StorageService storageService;
  private final StorageMediaProbe mediaProbe;
  private final StorageBlobManager blobManager;
  private final StorageBlobMapper blobMapper;
  private final CanvasDocumentMapper documentMapper;
  private final CanvasFunctionResourcePinMapper refMapper;
  private final CanvasResourceMapper resourceMapper;
  private final CanvasBlobPreviewService previewService;
  private final CanvasMediaProperties properties;
  private final TransactionTemplate transactionTemplate;

  public CanvasBlobResourceMaterializer(
      S3StorageService storageService,
      StorageMediaProbe mediaProbe,
      StorageBlobManager blobManager,
      StorageBlobMapper blobMapper,
      CanvasDocumentMapper documentMapper,
      CanvasFunctionResourcePinMapper refMapper,
      CanvasResourceMapper resourceMapper,
      CanvasBlobPreviewService previewService,
      CanvasMediaProperties properties,
      TransactionTemplate transactionTemplate) {
    this.storageService = Objects.requireNonNull(storageService, "storageService");
    this.mediaProbe = Objects.requireNonNull(mediaProbe, "mediaProbe");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.blobMapper = Objects.requireNonNull(blobMapper, "blobMapper");
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.refMapper = Objects.requireNonNull(refMapper, "refMapper");
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
    this.previewService = Objects.requireNonNull(previewService, "previewService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  @Override
  public CanvasResource materialize(
      UUID canvasId, UUID resourceId, String name, InputStream content) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(content, "content");
    CanvasResourceDO existing = resourceMapper.getById(canvasId, resourceId);
    if (existing != null) {
      return PostgresqlCanvasResourceRepository.toDomain(existing);
    }
    Path workDir = createWorkDir();
    UUID candidateBlobId = UUID.randomUUID();
    String originalKey = StorageObjectKeys.blobOriginal(candidateBlobId);
    boolean keepCandidateObject = false;
    try {
      Path original = workDir.resolve("original");
      Spooled spooled = copyBounded(content, original, MAX_MATERIALIZE_SIZE);
      try (InputStream originalContent = Files.newInputStream(original)) {
        storageService.putObject(originalKey, originalContent, spooled.sizeBytes(), OCTET_STREAM);
      }
      S3ObjectMetadata head = storageService.headObject(originalKey);
      StorageMediaFacts facts = mediaProbe.probe(originalKey, head);
      MaterializeOutcome outcome =
          transactionTemplate.execute(
              status ->
                  materializeInTransaction(
                      canvasId,
                      resourceId,
                      name,
                      candidateBlobId,
                      spooled.sha256Hex(),
                      spooled.sizeBytes(),
                      facts));
      keepCandidateObject = outcome.keepCandidateObject();
      if (!outcome.keepCandidateObject()) {
        // blob 去重或 resourceId 竞争落败：candidate 对象未成为持久资源内容，直接清理。
        storageService.deleteObjectIfExists(originalKey);
      }
      StorageBlobDO persistedBlob = blobMapper.getById(outcome.resource().getBlobId());
      if (persistedBlob == null) {
        throw new IllegalStateException(
            "materialized Resource references a missing blob: " + outcome.resource().getBlobId());
      }
      generatePreviewBestEffort(outcome.resource().getBlobId(), persistedBlob.getMediaType());
      return PostgresqlCanvasResourceRepository.toDomain(outcome.resource());
    } catch (IOException error) {
      if (!keepCandidateObject) {
        storageService.deleteObjectIfExists(originalKey);
      }
      throw new UncheckedIOException("failed to materialize canvas resource", error);
    } catch (RuntimeException error) {
      if (!keepCandidateObject) {
        storageService.deleteObjectIfExists(originalKey);
      }
      throw error;
    } finally {
      deleteRecursively(workDir);
    }
  }

  private MaterializeOutcome materializeInTransaction(
      UUID canvasId,
      UUID resourceId,
      String name,
      UUID candidateBlobId,
      String sha256Hex,
      long sizeBytes,
      StorageMediaFacts facts) {
    if (documentMapper.getByIdForUpdate(canvasId) == null) {
      throw new IllegalStateException("Canvas no longer exists: " + canvasId);
    }
    if (refMapper.findRunningOutputPins(canvasId, resourceId).size() != 1) {
      throw new IllegalStateException(
          "Function target is no longer pinned by exactly one RUNNING run: " + resourceId);
    }
    StorageBlobDO candidate = new StorageBlobDO();
    candidate.setId(candidateBlobId);
    candidate.setSha256(sha256Hex);
    candidate.setSizeBytes(sizeBytes);
    candidate.setMediaType(facts.mediaType());
    candidate.setWidth(facts.width());
    candidate.setHeight(facts.height());
    candidate.setDurationMs(facts.durationMs());
    candidate.setRefCount(1L);
    boolean inserted = blobMapper.insertActiveCandidate(candidate) == 1;
    UUID owningBlobId;
    if (inserted) {
      owningBlobId = candidateBlobId;
    } else {
      StorageBlobDO active = blobMapper.getActiveByHashAndSize(sha256Hex, sizeBytes);
      if (active == null) {
        throw new IllegalStateException(
            "blob dedup race: no ACTIVE blob found for hash " + sha256Hex);
      }
      blobManager.retain(active.getId());
      owningBlobId = active.getId();
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    CanvasResourceDO resource = new CanvasResourceDO();
    resource.setId(resourceId);
    resource.setCanvasId(canvasId);
    resource.setOwnerNodeId(null);
    resource.setResourceIndex(null);
    resource.setBlobId(owningBlobId);
    resource.setName(name);
    resource.setTextContent(null);
    resource.setCreatedAt(now);
    if (resourceMapper.insertIfAbsent(resource) != 1) {
      CanvasResourceDO winner = resourceMapper.getById(canvasId, resourceId);
      if (winner == null) {
        throw new IllegalStateException("resource insertIfAbsent race: winner row is missing");
      }
      if (inserted) {
        // 我们刚插入的 candidate 行引用减到 0 会转 DELETING，对象由 blob 管理器 afterCommit 清理。
        blobManager.release(candidateBlobId);
      } else {
        blobManager.release(owningBlobId);
      }
      return new MaterializeOutcome(winner, false);
    }
    return new MaterializeOutcome(resource, inserted);
  }

  private void generatePreviewBestEffort(UUID blobId, String mediaType) {
    try {
      previewService.ensurePreview(blobId, mediaType);
    } catch (RuntimeException error) {
      log.warn(
          "blob preview generation failed blobId={} type={}",
          blobId,
          error.getClass().getSimpleName());
    }
  }

  private Path createWorkDir() {
    try {
      Files.createDirectories(properties.getTempDir());
      return Files.createTempDirectory(properties.getTempDir(), "canvas-materialize-");
    } catch (IOException error) {
      throw new UncheckedIOException("failed to create canvas materialize temp directory", error);
    }
  }

  private Spooled copyBounded(InputStream content, Path target, long maxSize) throws IOException {
    try (OutputStream output = Files.newOutputStream(target)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      long total = 0L;
      int read;
      while ((read = content.read(buffer)) != -1) {
        total += read;
        if (total > maxSize) {
          throw new IllegalArgumentException("media exceeds maximum size");
        }
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
      return new Spooled(total, HexFormat.of().formatHex(digest.digest()));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  private static void deleteRecursively(Path path) {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              child -> {
                try {
                  Files.deleteIfExists(child);
                } catch (IOException error) {
                  log.warn(
                      "failed to delete temp path {} type={}",
                      child,
                      error.getClass().getSimpleName());
                }
              });
    } catch (IOException error) {
      log.warn("failed to walk temp path {} type={}", path, error.getClass().getSimpleName());
    }
  }

  private record Spooled(long sizeBytes, String sha256Hex) {}

  private record MaterializeOutcome(CanvasResourceDO resource, boolean keepCandidateObject) {}
}
