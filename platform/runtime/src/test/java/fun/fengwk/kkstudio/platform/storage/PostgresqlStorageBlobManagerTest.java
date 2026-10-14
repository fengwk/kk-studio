package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageObjectCleanupService;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

/** release 到零后的提交回调只做快速本地 wake，回滚不 wake，且绝不执行 S3。 */
class PostgresqlStorageBlobManagerTest {

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void committedReleaseWakesWithoutS3AndWakeFailureDoesNotEscape() {
    UUID blobId = UUID.randomUUID();
    StorageBlobRepository repository = deletingRepository(blobId);
    S3StorageService s3 = mock(S3StorageService.class);
    S3PresignService presign = mock(S3PresignService.class);
    StorageMaintenanceWakeup wakeup = mock(StorageMaintenanceWakeup.class);
    ObjectProvider<StorageMaintenanceWakeup> provider = provider(wakeup);
    PostgresqlStorageBlobManager manager = newManager(repository, s3, presign, provider);
    TransactionSynchronizationManager.initSynchronization();

    long started = System.nanoTime();
    assertTrue(manager.release(blobId));
    List<TransactionSynchronization> synchronizations =
        TransactionSynchronizationManager.getSynchronizations();
    synchronizations.forEach(TransactionSynchronization::afterCommit);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    verify(wakeup).wake();
    verify(s3, never()).deleteObject(anyString());
    assertTrue(elapsedMillis < 1_000, "commit callback must return quickly");

    when(provider.getObject()).thenThrow(new IllegalStateException("wake failed"));
    synchronizations.forEach(TransactionSynchronization::afterCommit);
  }

  @Test
  void rolledBackReleaseDoesNotWake() {
    UUID blobId = UUID.randomUUID();
    StorageMaintenanceWakeup wakeup = mock(StorageMaintenanceWakeup.class);
    PostgresqlStorageBlobManager manager =
        newManager(
            deletingRepository(blobId),
            mock(S3StorageService.class),
            mock(S3PresignService.class),
            provider(wakeup));
    TransactionSynchronizationManager.initSynchronization();

    assertTrue(manager.release(blobId));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    verify(wakeup, never()).wake();
  }

  /** 测试意图：DELETING 清扫必须先在同一短事务内登记对象清理记录，提交之后才允许事务外删除对象； 否则对象删除与迟到写入之间会留下无事实残留。 */
  @Test
  void deletingSweepRegistersObjectKeysBeforeDeletingObjects() {
    UUID blobId = UUID.randomUUID();
    StorageBlobRepository repository = mock(StorageBlobRepository.class);
    when(repository.listDeletingIds(StorageBlobManager.MAX_SWEEP_BATCH))
        .thenReturn(List.of(blobId));
    when(repository.getById(blobId)).thenReturn(deletingBlob(blobId));
    when(repository.deleteDeleting(blobId)).thenReturn(true);
    S3StorageService s3 = mock(S3StorageService.class);
    StorageObjectCleanupService cleanups = mock(StorageObjectCleanupService.class);
    PostgresqlStorageBlobManager manager =
        new PostgresqlStorageBlobManager(
            repository,
            s3,
            mock(S3PresignService.class),
            provider(mock(StorageMaintenanceWakeup.class)),
            cleanups,
            mock(PlatformTransactionManager.class));

    assertEquals(1, manager.sweepDeleting());

    InOrder order = inOrder(cleanups, s3);
    order.verify(cleanups).enqueue(StorageObjectKeys.blobPreview(blobId));
    order.verify(cleanups).enqueue(StorageObjectKeys.blobOriginal(blobId));
    order.verify(s3).deleteObjectIfExists(StorageObjectKeys.blobPreview(blobId));
    order.verify(s3).deleteObjectIfExists(StorageObjectKeys.blobOriginal(blobId));
    verify(repository).deleteDeleting(blobId);
  }

  /** 复核失败（已非 DELETING 或被并发 retain）时不得登记更不得删除任何对象。 */
  @Test
  void deletingSweepSkipsBlobThatIsNoLongerDeleting() {
    UUID blobId = UUID.randomUUID();
    StorageBlobRepository repository = mock(StorageBlobRepository.class);
    when(repository.listDeletingIds(StorageBlobManager.MAX_SWEEP_BATCH))
        .thenReturn(List.of(blobId));
    StorageBlob active = new StorageBlob();
    active.setId(blobId);
    active.setRefCount(1L);
    active.setState(StorageBlobState.ACTIVE);
    when(repository.getById(blobId)).thenReturn(active);
    S3StorageService s3 = mock(S3StorageService.class);
    StorageObjectCleanupService cleanups = mock(StorageObjectCleanupService.class);
    PostgresqlStorageBlobManager manager =
        new PostgresqlStorageBlobManager(
            repository,
            s3,
            mock(S3PresignService.class),
            provider(mock(StorageMaintenanceWakeup.class)),
            cleanups,
            mock(PlatformTransactionManager.class));

    assertEquals(0, manager.sweepDeleting());

    verify(cleanups, never()).enqueue(anyString());
    verify(s3, never()).deleteObjectIfExists(anyString());
    verify(repository, never()).deleteDeleting(blobId);
  }

  private static StorageBlob deletingBlob(UUID blobId) {
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setRefCount(0L);
    blob.setState(StorageBlobState.DELETING);
    return blob;
  }

  private static PostgresqlStorageBlobManager newManager(
      StorageBlobRepository repository,
      S3StorageService s3,
      S3PresignService presign,
      ObjectProvider<StorageMaintenanceWakeup> provider) {
    return new PostgresqlStorageBlobManager(
        repository,
        s3,
        presign,
        provider,
        mock(StorageObjectCleanupService.class),
        mock(PlatformTransactionManager.class));
  }

  private static StorageBlobRepository deletingRepository(UUID blobId) {
    StorageBlobRepository repository = mock(StorageBlobRepository.class);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setRefCount(0L);
    blob.setState(StorageBlobState.DELETING);
    when(repository.releaseOnce(blobId)).thenReturn(true);
    when(repository.getById(blobId)).thenReturn(blob);
    return repository;
  }

  private static <T> ObjectProvider<T> provider(T value) {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(value);
    return provider;
  }
}
