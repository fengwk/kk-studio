package fun.fengwk.kkstudio.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/**
 * {@link StorageStartupRecovery} 排空语义：批次达到上限时继续下一轮，两个批次都低于上限即停止。
 *
 * <p>单行失败在批内被吞掉（返回小于上限），因此重复失败会让批次立刻低于上限并退出，不会死循环。
 *
 * <p>S3 未启用时服务 bean 缺失：监听器必须通过 ObjectProvider 感知缺失并安全跳过（不做任何回收，也不抛异常）。
 *
 * @author fengwk
 */
class StorageStartupRecoveryTest {

  @Test
  void drainsBoundedBatchesUntilBothFallBelowMaximum() {
    FakeUploadService uploads = new FakeUploadService(16, 16, 5);
    FakeBlobManager blobs = new FakeBlobManager(16, 0, 0);

    new StorageStartupRecovery(provider(uploads), provider(blobs)).onApplicationEvent(null);

    assertEquals(
        3,
        uploads.expireCalls(),
        "expire must keep draining while the last batch was full (16, 16, 5)");
    assertEquals(
        3, blobs.sweepCalls(), "sweep must keep draining while the last batch was full (16, 0, 0)");
  }

  @Test
  void stopsAfterOneRoundWhenFailuresKeepBatchesBelowMaximum() {
    // 模拟所有行都失败：批内吞掉异常后返回 0（小于上限），循环必须立即退出而非无限重试。
    FakeUploadService uploads = new FakeUploadService(0);
    FakeBlobManager blobs = new FakeBlobManager(0);

    new StorageStartupRecovery(provider(uploads), provider(blobs)).onApplicationEvent(null);

    assertEquals(1, uploads.expireCalls(), "failure-drained batch must not trigger another round");
    assertEquals(1, blobs.sweepCalls());
  }

  @Test
  void continuesWhileEitherBatchIsStillFull() {
    FakeUploadService uploads = new FakeUploadService(16, 16, 3);
    FakeBlobManager blobs = new FakeBlobManager(16, 16, 0);

    new StorageStartupRecovery(provider(uploads), provider(blobs)).onApplicationEvent(null);

    assertEquals(3, uploads.expireCalls(), "expire: 16 -> 16 (sweep full) -> 3 (drained)");
    assertEquals(3, blobs.sweepCalls(), "sweep: 16 -> 16 -> 0 (drained)");
  }

  @Test
  void swallowsTopLevelFailuresAndStops() {
    FakeUploadService uploads = new FakeUploadService(16, 16);
    uploads.failOnCall = 2;
    FakeBlobManager blobs = new FakeBlobManager(0);

    new StorageStartupRecovery(provider(uploads), provider(blobs)).onApplicationEvent(null);

    assertEquals(2, uploads.expireCalls(), "top-level failure must abort recovery, not retry");
    assertEquals(1, blobs.sweepCalls());
  }

  @Test
  void skipsRecoveryWhenStorageServicesAreAbsent() {
    // S3 未启用时 StorageUploadService/StorageBlobManager 不装配：监听器必须安全跳过。
    ObjectProvider<StorageUploadService> uploads = mock(ObjectProvider.class);
    ObjectProvider<StorageBlobManager> blobs = mock(ObjectProvider.class);

    new StorageStartupRecovery(uploads, blobs).onApplicationEvent(null);

    verify(uploads).getIfAvailable();
    verify(blobs).getIfAvailable();
  }

  private static <T> ObjectProvider<T> provider(T value) {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  /** 过期批次结果队列假件：每轮 expireOnce 返回队列中的一个值，可注入一次指定轮次的顶层失败。 */
  private static final class FakeUploadService implements StorageUploadService {

    private final Queue<Integer> results = new ArrayDeque<>();
    private int expireCalls;
    private int failOnCall = -1;

    private FakeUploadService(int... results) {
      for (int result : results) {
        this.results.add(result);
      }
    }

    @Override
    public int expireOnce() {
      expireCalls++;
      if (expireCalls == failOnCall) {
        throw new IllegalStateException("db down");
      }
      Integer result = results.poll();
      if (result == null) {
        throw new AssertionError("expireOnce called more times than configured results");
      }
      return result;
    }

    private int expireCalls() {
      return expireCalls;
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

  /** DELETING 清扫批次结果队列假件。 */
  private static final class FakeBlobManager implements StorageBlobManager {

    private final Queue<Integer> results = new ArrayDeque<>();
    private int sweepCalls;

    private FakeBlobManager(int... results) {
      for (int result : results) {
        this.results.add(result);
      }
    }

    @Override
    public int sweepDeleting() {
      sweepCalls++;
      Integer result = results.poll();
      if (result == null) {
        throw new AssertionError("sweepDeleting called more times than configured results");
      }
      return result;
    }

    private int sweepCalls() {
      return sweepCalls;
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
