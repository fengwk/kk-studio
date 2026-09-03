package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Storage Maintenance 的 startup/poll/wake/close 生命周期与 S3-disabled 跳过语义。 */
class StorageMaintenanceTest {

  @Test
  void startupWakeAndFixedDelayPollDriveMaintenance() throws Exception {
    CountingUploadService uploads = new CountingUploadService(2);
    CountingBlobManager blobs = new CountingBlobManager(2);
    StorageMaintenance maintenance =
        new StorageMaintenance(
            provider(uploads), provider(blobs), properties(Duration.ofMillis(20)));
    try {
      maintenance.start();

      assertTrue(uploads.await(), "startup wake and periodic poll must both run");
      assertTrue(blobs.await());
      assertTrue(maintenance.isRunning());
      maintenance.start();
    } finally {
      maintenance.close();
    }
  }

  @Test
  void startupWakeDrainsFullBatchesUntilBothServicesAreIdle() throws Exception {
    StorageUploadService uploads = mock(StorageUploadService.class);
    Queue<Integer> uploadResults = new ArrayDeque<>(List.of(16, 16, 5));
    CountDownLatch uploadCalls = new CountDownLatch(3);
    when(uploads.expireOnce())
        .thenAnswer(
            ignored -> {
              uploadCalls.countDown();
              return uploadResults.remove();
            });
    StorageBlobManager blobs = mock(StorageBlobManager.class);
    Queue<Integer> blobResults = new ArrayDeque<>(List.of(16, 0, 0));
    CountDownLatch blobCalls = new CountDownLatch(3);
    when(blobs.sweepDeleting())
        .thenAnswer(
            ignored -> {
              blobCalls.countDown();
              return blobResults.remove();
            });
    StorageMaintenance maintenance =
        new StorageMaintenance(provider(uploads), provider(blobs), properties(Duration.ofHours(1)));
    try {
      maintenance.start();

      assertTrue(uploadCalls.await(5, TimeUnit.SECONDS));
      assertTrue(blobCalls.await(5, TimeUnit.SECONDS));
      verify(uploads, times(3)).expireOnce();
      verify(blobs, times(3)).sweepDeleting();
    } finally {
      maintenance.close();
    }
  }

  @Test
  void concurrentWakeRequestsAreCoalescedAndCloseIsIdempotent() throws Exception {
    BlockingUploadService uploads = new BlockingUploadService();
    CountingBlobManager blobs = new CountingBlobManager(2);
    StorageMaintenance maintenance =
        new StorageMaintenance(provider(uploads), provider(blobs), properties(Duration.ofHours(1)));
    maintenance.start();
    assertTrue(uploads.entered.await(5, TimeUnit.SECONDS));

    for (int i = 0; i < 100; i++) {
      maintenance.wake();
    }
    uploads.release.countDown();
    assertTrue(uploads.finished.await(5, TimeUnit.SECONDS));

    maintenance.close();
    maintenance.close();
    assertFalse(maintenance.isRunning());
    int callsAfterClose = uploads.calls.get();
    maintenance.wake();
    assertEquals(callsAfterClose, uploads.calls.get(), "wake after close must be a no-op");
    assertThrows(IllegalStateException.class, maintenance::start);
  }

  @Test
  void absentStorageServicesAreSafelySkipped() throws Exception {
    StorageMaintenance maintenance =
        new StorageMaintenance(emptyProvider(), emptyProvider(), properties(Duration.ofMillis(20)));
    maintenance.start();
    try {
      maintenance.wake();
      assertTrue(maintenance.isRunning());
    } finally {
      maintenance.close();
    }
  }

  /**
   * 测试意图：验证 SmartLifecycle 的 start/stop 可重复调用语义——stop 仅暂停后台维护并释放当前 executor， 之后仍可被 start 安全重启；只有
   * AutoCloseable.close 后才会永久关闭并拒绝 start。
   */
  @Test
  void stopSuspendsMaintenanceAndCanBeRestartedUntilClosed() throws Exception {
    CountingUploadService uploads = new CountingUploadService(1);
    CountingBlobManager blobs = new CountingBlobManager(1);
    StorageMaintenance maintenance =
        new StorageMaintenance(provider(uploads), provider(blobs), properties(Duration.ofHours(1)));
    try {
      maintenance.start();
      assertTrue(uploads.await());
      assertTrue(blobs.await());
      assertTrue(maintenance.isRunning());

      maintenance.stop();
      assertFalse(maintenance.isRunning());

      int callsBefore = uploads.calls.get();
      maintenance.wake();
      assertEquals(callsBefore, uploads.calls.get(), "wake while stopped must be a no-op");

      maintenance.start();
      assertTrue(maintenance.isRunning());

      maintenance.stop();
      assertFalse(maintenance.isRunning());
    } finally {
      maintenance.close();
    }
    assertThrows(
        IllegalStateException.class,
        maintenance::start,
        "start after close must throw IllegalStateException");
  }

  @Test
  void stopCallbackRunsAndServiceFailuresAreIsolated() throws Exception {
    StorageMaintenance stopped =
        new StorageMaintenance(emptyProvider(), emptyProvider(), properties(Duration.ofHours(1)));
    AtomicBoolean callbackRan = new AtomicBoolean();
    stopped.stop(() -> callbackRan.set(true));
    assertTrue(callbackRan.get());

    CountDownLatch uploadFailed = new CountDownLatch(1);
    StorageUploadService uploads = mock(StorageUploadService.class);
    when(uploads.expireOnce())
        .thenAnswer(
            ignored -> {
              uploadFailed.countDown();
              throw new IllegalStateException("upload failed");
            });
    CountDownLatch blobFailed = new CountDownLatch(1);
    StorageBlobManager blobs = mock(StorageBlobManager.class);
    when(blobs.sweepDeleting())
        .thenAnswer(
            ignored -> {
              blobFailed.countDown();
              throw new IllegalStateException("blob failed");
            });
    StorageMaintenance maintenance =
        new StorageMaintenance(provider(uploads), provider(blobs), properties(Duration.ofHours(1)));
    try {
      maintenance.start();
      assertTrue(uploadFailed.await(5, TimeUnit.SECONDS));
      assertTrue(blobFailed.await(5, TimeUnit.SECONDS));
      assertTrue(maintenance.isRunning());
    } finally {
      maintenance.close();
    }
  }

  @Test
  void drainFailureAndRejectedSubmissionAreContainedForLaterPolls() throws Exception {
    @SuppressWarnings("unchecked")
    ObjectProvider<StorageUploadService> failingUploads = mock(ObjectProvider.class);
    CountDownLatch failedDrain = new CountDownLatch(1);
    when(failingUploads.getIfAvailable())
        .thenAnswer(
            ignored -> {
              failedDrain.countDown();
              throw new IllegalStateException("provider failed");
            });
    StorageMaintenance failing =
        new StorageMaintenance(failingUploads, emptyProvider(), properties(Duration.ofHours(1)));
    failing.start();
    try {
      assertTrue(failedDrain.await(5, TimeUnit.SECONDS));
      assertTrue(failing.isRunning(), "drain failure must not terminate the lifecycle");
    } finally {
      failing.close();
    }

    CountingUploadService uploads = new CountingUploadService(1);
    StorageMaintenance maintenance =
        new StorageMaintenance(
            provider(uploads),
            provider(new CountingBlobManager(1)),
            properties(Duration.ofHours(1)));
    maintenance.start();
    assertTrue(uploads.await());
    ScheduledExecutorService owned = executor(maintenance);
    ScheduledExecutorService rejected = Executors.newSingleThreadScheduledExecutor();
    rejected.shutdownNow();
    setExecutor(maintenance, rejected);
    try {
      maintenance.wake();
      setExecutor(maintenance, null);
      maintenance.wake();
      assertTrue(maintenance.isRunning());
    } finally {
      setExecutor(maintenance, owned);
      maintenance.close();
    }
  }

  @Test
  void rejectsInvalidPollDurations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageMaintenance(emptyProvider(), emptyProvider(), properties(Duration.ZERO)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StorageMaintenance(
                emptyProvider(), emptyProvider(), properties(Duration.ofNanos(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StorageMaintenance(
                emptyProvider(), emptyProvider(), properties(Duration.ofSeconds(Long.MAX_VALUE))));
  }

  private static StorageMaintenanceProperties properties(Duration pollDelay) {
    StorageMaintenanceProperties properties = new StorageMaintenanceProperties();
    properties.setPollDelay(pollDelay);
    return properties;
  }

  private static ScheduledExecutorService executor(StorageMaintenance maintenance)
      throws ReflectiveOperationException {
    Field field = StorageMaintenance.class.getDeclaredField("executor");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(maintenance);
  }

  private static void setExecutor(StorageMaintenance maintenance, ScheduledExecutorService executor)
      throws ReflectiveOperationException {
    Field field = StorageMaintenance.class.getDeclaredField("executor");
    field.setAccessible(true);
    field.set(maintenance, executor);
  }

  private static <T> ObjectProvider<T> provider(T value) {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private static <T> ObjectProvider<T> emptyProvider() {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    return provider;
  }

  private static class CountingUploadService implements StorageUploadService {

    protected final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch latch;

    private CountingUploadService(int expectedCalls) {
      this.latch = new CountDownLatch(expectedCalls);
    }

    @Override
    public int expireOnce() {
      calls.incrementAndGet();
      latch.countDown();
      return 0;
    }

    private boolean await() throws InterruptedException {
      return latch.await(5, TimeUnit.SECONDS);
    }

    @Override
    public StorageUploadDTO reserve(StorageUploadReserveRequestDTO request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StorageUploadDTO complete(UUID uploadId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(UUID uploadId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReadyUpload lockReady(UUID uploadId) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class BlockingUploadService extends CountingUploadService {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(2);

    private BlockingUploadService() {
      super(0);
    }

    @Override
    public int expireOnce() {
      int current = calls.incrementAndGet();
      if (current == 1) {
        entered.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
      }
      finished.countDown();
      return 0;
    }
  }

  private static final class CountingBlobManager implements StorageBlobManager {

    private final CountDownLatch latch;

    private CountingBlobManager(int expectedCalls) {
      this.latch = new CountDownLatch(expectedCalls);
    }

    @Override
    public int sweepDeleting() {
      latch.countDown();
      return 0;
    }

    private boolean await() throws InterruptedException {
      return latch.await(5, TimeUnit.SECONDS);
    }

    @Override
    public StorageBlob retain(UUID blobId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean release(UUID blobId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StorageBlob getBlob(UUID blobId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoragePresignedUrlDTO presignOriginalUrl(UUID blobId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoragePresignedUrlDTO presignPreviewUrl(UUID blobId) {
      throw new UnsupportedOperationException();
    }
  }
}
