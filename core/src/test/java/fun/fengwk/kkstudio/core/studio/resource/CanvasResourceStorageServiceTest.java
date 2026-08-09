package fun.fengwk.kkstudio.core.studio.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.storage.S3ObjectContent;
import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourcePaths;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasUpload;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class CanvasResourceStorageServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  @TempDir Path tempDir;

  private InMemoryResourceRepository resources;
  private InMemoryUploadRepository uploads;
  private FileBackedS3Storage storage;
  private FakeMediaProcessor mediaProcessor;
  private CanvasResourceStorageService service;

  @BeforeEach
  void setUp() {
    resources = new InMemoryResourceRepository();
    uploads = new InMemoryUploadRepository();
    storage = new FileBackedS3Storage(tempDir.resolve("s3"));
    mediaProcessor = new FakeMediaProcessor(tempDir.resolve("media"));
    CanvasDocumentMapper documents = mock(CanvasDocumentMapper.class);
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(1L);
    when(documents.getById(1L)).thenReturn(document);
    PostgresqlSequenceIdGenerator ids = mock(PostgresqlSequenceIdGenerator.class);
    AtomicLong sequence = new AtomicLong(100L);
    when(ids.next()).thenAnswer(ignored -> sequence.incrementAndGet());
    CanvasMediaProperties properties = new CanvasMediaProperties();
    service =
        new CanvasResourceStorageService(
            documents,
            resources,
            uploads,
            ids,
            storage,
            new FakePresignService(),
            mediaProcessor,
            new SynchronizedCommitter(resources, uploads),
            properties,
            () -> NOW);
  }

  /** Reserve 固定服务端 key、隐藏 bucket/key，并在写 Upload 前校验 kind/扩展名/大小。 */
  @Test
  void reserveValidatesRequestAndReturnsCanvasWrapper() {
    CanvasUploadReservation reservation =
        service.reserve(1L, CanvasResourceKind.IMAGE, " photo.PNG ", "image/png", 3L);
    CanvasUpload upload = uploads.findById(1L, reservation.uploadId()).orElseThrow();

    assertEquals("photo.PNG", upload.filename());
    assertEquals("PUT", reservation.method());
    assertFalse(reservation.url().contains("bucket="));
    assertTrue(
        reservation.url().contains(CanvasResourcePaths.original(1L, reservation.uploadId())));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.reserve(1L, CanvasResourceKind.TEXT, "note.md", "text/markdown", 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.reserve(1L, CanvasResourceKind.IMAGE, "video.mp4", "video/mp4", 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.reserve(
                1L,
                CanvasResourceKind.AUDIO,
                "large.mp3",
                "audio/mpeg",
                FfmpegCanvasMediaProcessor.MAX_AUDIO_SIZE + 1L));
  }

  /** Complete 先 HEAD/流式处理，再上传 preview，最终原子替换 Upload；重试直接返回同一 Resource。 */
  @Test
  void completeCreatesResourceAndIsIdempotent() {
    CanvasUploadReservation reservation =
        service.reserve(1L, CanvasResourceKind.IMAGE, "photo.png", "application/octet-stream", 3L);
    storage.writeOriginal(
        CanvasResourcePaths.original(1L, reservation.uploadId()), new byte[] {1, 2, 3});

    CanvasResource first = service.complete(1L, reservation.uploadId());
    CanvasResource replay = service.complete(1L, reservation.uploadId());

    assertEquals(first, replay);
    assertEquals("image/png", first.mediaType());
    assertEquals("{\"width\":16,\"height\":12}", first.metadataJson());
    assertTrue(storage.exists(CanvasResourcePaths.preview(1L, reservation.uploadId())));
    assertTrue(uploads.findById(1L, reservation.uploadId()).isEmpty());
    assertEquals(1, mediaProcessor.calls.get());
  }

  /** 两个同时 finalize 的调用在提交阶段收敛到同一行和同一返回值。 */
  @Test
  void concurrentCompleteReturnsOneResource() throws Exception {
    CanvasUploadReservation reservation =
        service.reserve(1L, CanvasResourceKind.VIDEO, "clip.mp4", "video/mp4", 3L);
    storage.writeOriginal(
        CanvasResourcePaths.original(1L, reservation.uploadId()), new byte[] {1, 2, 3});
    mediaProcessor.barrier = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanvasResource> first =
          executor.submit(() -> service.complete(1L, reservation.uploadId()));
      Future<CanvasResource> second =
          executor.submit(() -> service.complete(1L, reservation.uploadId()));
      CanvasResource firstResult = first.get();
      CanvasResource secondResult = second.get();

      assertEquals(firstResult, secondResult);
      assertEquals(1, resources.byId.size());
      assertTrue(uploads.findById(1L, reservation.uploadId()).isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  /** wrong canvas、过期、HEAD size mismatch 与 S3 missing 在媒体处理前失败。 */
  @Test
  void completeRejectsInvalidUploadStateBeforeProbe() {
    CanvasUploadReservation valid =
        service.reserve(1L, CanvasResourceKind.AUDIO, "sound.mp3", "audio/mpeg", 3L);
    storage.writeOriginal(CanvasResourcePaths.original(1L, valid.uploadId()), new byte[] {1, 2});
    assertReason(
        CanvasResourceStorageException.Reason.NOT_FOUND,
        () -> service.complete(2L, valid.uploadId()));
    assertThrows(IllegalArgumentException.class, () -> service.complete(1L, valid.uploadId()));

    uploads.add(
        new CanvasUpload(
            500L,
            1L,
            CanvasResourceKind.AUDIO,
            "expired.mp3",
            "audio/mpeg",
            1L,
            NOW,
            NOW.minusSeconds(1)));
    assertReason(CanvasResourceStorageException.Reason.EXPIRED, () -> service.complete(1L, 500L));

    uploads.add(
        new CanvasUpload(
            501L,
            1L,
            CanvasResourceKind.AUDIO,
            "missing.mp3",
            "audio/mpeg",
            1L,
            NOW.plusSeconds(60),
            NOW));
    assertReason(
        CanvasResourceStorageException.Reason.S3_MISSING, () -> service.complete(1L, 501L));
    assertEquals(0, mediaProcessor.calls.get());
  }

  /** Resource URL 严格同 Canvas，并按 TEXT/AUDIO preview/original 形状拒绝非法签名。 */
  @Test
  void resourceUrlsEnforceCanvasAndKind() {
    CanvasResource image = resource(601L, CanvasResourceKind.IMAGE);
    CanvasResource audio = resource(602L, CanvasResourceKind.AUDIO);
    CanvasResource text =
        new CanvasResource(
            603L, 1L, CanvasResourceKind.TEXT, "text/markdown", "note", 1L, "x", "{}", NOW);
    resources.add(image);
    resources.add(audio);
    resources.add(text);

    assertTrue(service.originalUrl(1L, image.id()).url().contains("/original"));
    assertTrue(service.previewUrl(1L, image.id()).url().contains("/preview.webp"));
    assertTrue(service.originalUrl(1L, audio.id()).url().contains("/original"));
    assertThrows(IllegalArgumentException.class, () -> service.previewUrl(1L, audio.id()));
    assertThrows(IllegalArgumentException.class, () -> service.originalUrl(1L, text.id()));
    assertReason(
        CanvasResourceStorageException.Reason.NOT_FOUND, () -> service.originalUrl(2L, image.id()));
  }

  /** Materializer 流式写原件、复用 preview/insert，并在重复 id 时不再消费调用方流。 */
  @Test
  void materializerCreatesAndReplaysResourceWithoutHttpDto() {
    CanvasResource first =
        service.materialize(
            1L,
            700L,
            CanvasResourceKind.IMAGE,
            "generated.png",
            "application/octet-stream",
            3L,
            new ByteArrayInputStream(new byte[] {4, 5, 6}));
    InputStream mustNotRead =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("idempotent materialize must not consume content");
          }
        };
    CanvasResource replay =
        service.materialize(
            1L, 700L, CanvasResourceKind.IMAGE, "ignored.png", "image/png", 3L, mustNotRead);

    assertEquals(first, replay);
    assertTrue(storage.exists(CanvasResourcePaths.original(1L, 700L)));
    assertTrue(storage.exists(CanvasResourcePaths.preview(1L, 700L)));
    assertEquals(1, mediaProcessor.calls.get());
  }

  private CanvasResource resource(long id, CanvasResourceKind kind) {
    return new CanvasResource(
        id,
        1L,
        kind,
        kind == CanvasResourceKind.AUDIO ? "audio/mpeg" : "image/png",
        "resource",
        3L,
        null,
        "{}",
        NOW);
  }

  private void assertReason(CanvasResourceStorageException.Reason reason, Runnable invocation) {
    CanvasResourceStorageException error =
        assertThrows(CanvasResourceStorageException.class, invocation::run);
    assertEquals(reason, error.reason());
  }

  private static final class FakePresignService implements S3PresignService {

    @Override
    public S3PresignedResponseDTO presignUpload(
        String key, String contentType, Long expiresInSeconds) {
      return response("PUT", key, Map.of("Content-Type", contentType));
    }

    @Override
    public S3PresignedResponseDTO presignDownload(String key, Long expiresInSeconds) {
      return response("GET", key, Map.of());
    }

    private S3PresignedResponseDTO response(
        String method, String key, Map<String, String> headers) {
      return S3PresignedResponseDTO.builder()
          .bucket("hidden-bucket")
          .key(key)
          .method(method)
          .url("https://s3.fengwk.fun/" + key)
          .headers(headers)
          .expiresAt(NOW.plusSeconds(600).toString())
          .build();
    }
  }

  private static final class InMemoryResourceRepository implements CanvasResourceRepository {

    private final Map<Long, CanvasResource> byId = new ConcurrentHashMap<>();

    @Override
    public void add(CanvasResource resource) {
      if (byId.putIfAbsent(resource.id(), resource) != null) {
        throw new IllegalArgumentException("duplicate");
      }
    }

    @Override
    public boolean addIfAbsent(CanvasResource resource) {
      return byId.putIfAbsent(resource.id(), resource) == null;
    }

    @Override
    public Optional<CanvasResource> findById(long canvasId, long resourceId) {
      return Optional.ofNullable(byId.get(resourceId))
          .filter(resource -> resource.canvasId() == canvasId);
    }

    @Override
    public Optional<CanvasResource> findById(long resourceId) {
      return Optional.ofNullable(byId.get(resourceId));
    }

    @Override
    public List<CanvasResource> findByIds(long canvasId, List<Long> resourceIds) {
      return resourceIds.stream()
          .map(byId::get)
          .filter(resource -> resource != null && resource.canvasId() == canvasId)
          .toList();
    }
  }

  private static final class InMemoryUploadRepository implements CanvasUploadRepository {

    private final Map<Long, CanvasUpload> byId = new ConcurrentHashMap<>();

    @Override
    public void add(CanvasUpload upload) {
      byId.put(upload.id(), upload);
    }

    @Override
    public Optional<CanvasUpload> findById(long canvasId, long uploadId) {
      return Optional.ofNullable(byId.get(uploadId))
          .filter(upload -> upload.canvasId() == canvasId);
    }

    @Override
    public Optional<CanvasUpload> findByIdForUpdate(long canvasId, long uploadId) {
      return findById(canvasId, uploadId);
    }

    @Override
    public boolean delete(long canvasId, long uploadId) {
      return byId.computeIfPresent(
              uploadId, (ignored, upload) -> upload.canvasId() == canvasId ? null : upload)
          == null;
    }
  }

  private static final class SynchronizedCommitter extends CanvasResourceCommitter {

    private final InMemoryResourceRepository resources;
    private final InMemoryUploadRepository uploads;

    private SynchronizedCommitter(
        InMemoryResourceRepository resources, InMemoryUploadRepository uploads) {
      super(resources, uploads);
      this.resources = resources;
      this.uploads = uploads;
    }

    @Override
    public synchronized CanvasResource commitUpload(
        CanvasUpload expectedUpload, CanvasResource candidate) {
      CanvasResource existing =
          resources.findById(candidate.canvasId(), candidate.id()).orElse(null);
      if (existing != null) {
        return existing;
      }
      if (uploads.findById(expectedUpload.canvasId(), expectedUpload.id()).isEmpty()) {
        return resources
            .findById(candidate.canvasId(), candidate.id())
            .orElseThrow(() -> new IllegalStateException("missing upload and resource"));
      }
      resources.addIfAbsent(candidate);
      uploads.delete(expectedUpload.canvasId(), expectedUpload.id());
      return resources.findById(candidate.canvasId(), candidate.id()).orElseThrow();
    }

    @Override
    public synchronized CanvasResource commitMaterialized(CanvasResource candidate) {
      CanvasResource existing = resources.findById(candidate.id()).orElse(null);
      if (existing != null) {
        if (existing.canvasId() != candidate.canvasId()) {
          throw new IllegalArgumentException("wrong canvas");
        }
        return existing;
      }
      resources.addIfAbsent(candidate);
      return resources.findById(candidate.id()).orElseThrow();
    }
  }

  private static final class FakeMediaProcessor implements CanvasMediaProcessor {

    private final Path root;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch barrier;

    private FakeMediaProcessor(Path root) {
      this.root = root;
    }

    @Override
    public CanvasProcessedMedia process(
        CanvasResourceKind kind, InputStream content, long expectedSize) {
      calls.incrementAndGet();
      awaitBarrier();
      try {
        Files.createDirectories(root);
        Path workDir = Files.createTempDirectory(root, "canvas-resource-");
        Path original = workDir.resolve("original");
        Files.copy(content, original);
        if (Files.size(original) != expectedSize) {
          CanvasProcessedMedia.deleteRecursively(workDir);
          throw new IllegalArgumentException("media size mismatch");
        }
        Path preview = null;
        if (kind != CanvasResourceKind.AUDIO) {
          preview = workDir.resolve("preview.webp");
          Files.write(preview, new byte[] {9, 8});
        }
        String mediaType =
            switch (kind) {
              case IMAGE -> "image/png";
              case VIDEO -> "video/mp4";
              case AUDIO -> "audio/mpeg";
              case TEXT -> throw new IllegalArgumentException("TEXT");
            };
        return new CanvasProcessedMedia(
            workDir, original, preview, expectedSize, mediaType, "{\"width\":16,\"height\":12}");
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    private void awaitBarrier() {
      CountDownLatch current = barrier;
      if (current == null) {
        return;
      }
      current.countDown();
      try {
        current.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  private static final class FileBackedS3Storage implements S3StorageService {

    private final Path root;

    private FileBackedS3Storage(Path root) {
      this.root = root;
    }

    @Override
    public PutObjectResponse putObject(
        String key, InputStream content, long contentLength, String contentType) {
      try {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(target) != contentLength) {
          throw new IllegalArgumentException("content length mismatch");
        }
        return PutObjectResponse.builder().build();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public boolean exists(String key) {
      return Files.exists(path(key));
    }

    @Override
    public S3ObjectMetadata headObject(String key) {
      Path path = path(key);
      if (!Files.exists(path)) {
        throw NoSuchKeyException.builder().message("missing").build();
      }
      try {
        return new S3ObjectMetadata(Files.size(path), "application/octet-stream", null);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public S3ObjectStream readObject(String key) {
      S3ObjectMetadata metadata = headObject(key);
      try {
        return new S3ObjectStream(Files.newInputStream(path(key)), metadata);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void deleteObject(String key) {
      try {
        Files.deleteIfExists(path(key));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public String getPublicUrl(String key) {
      return "https://s3.fengwk.fun/" + key;
    }

    @Override
    public byte[] download(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public S3ObjectContent download(String key, long maxSizeBytes) {
      throw new UnsupportedOperationException();
    }

    private void writeOriginal(String key, byte[] bytes) {
      putObject(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    private Path path(String key) {
      return root.resolve(key);
    }
  }
}
