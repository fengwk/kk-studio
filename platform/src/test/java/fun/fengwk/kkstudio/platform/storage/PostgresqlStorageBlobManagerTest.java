package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
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
    PostgresqlStorageBlobManager manager =
        new PostgresqlStorageBlobManager(repository, s3, presign, provider);
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

    when(provider.getIfAvailable()).thenThrow(new IllegalStateException("wake failed"));
    synchronizations.forEach(TransactionSynchronization::afterCommit);
  }

  @Test
  void rolledBackReleaseDoesNotWake() {
    UUID blobId = UUID.randomUUID();
    StorageMaintenanceWakeup wakeup = mock(StorageMaintenanceWakeup.class);
    PostgresqlStorageBlobManager manager =
        new PostgresqlStorageBlobManager(
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
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
