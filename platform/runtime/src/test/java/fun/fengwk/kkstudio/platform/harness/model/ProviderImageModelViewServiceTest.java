package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.io.InputStream;
import java.util.UUID;

class ProviderImageModelViewServiceTest {

  @Test
  void persistsScaledViewAndReusesItForTheSameSourceAndTier() throws Exception {
    UUID sourceBlobId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID viewBlobId = UUID.randomUUID();
    byte[] sourceBytes = TestImages.png(4000, 2000);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(contentService.readBlobContent(
            sourceBlobId, ProviderResourceMaterializer.maxInlineBlobBytes()))
        .thenReturn(
            new StorageBlobContent(sourceBlobId, sourceBytes, "image/png", sourceBytes.length));

    StorageUploadService uploadService = mock(StorageUploadService.class);
    when(uploadService.stage(anyString(), eq("image/png"), any(InputStream.class), anyLong()))
        .thenReturn(
            new StorageUploadService.StagedUpload(
                uploadId, viewBlobId, "model-view.png", "image/png", 1234, "0".repeat(64)));
    when(uploadService.lockReady(uploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(viewBlobId, "model-view.png"));

    StorageBlobRepository blobRepository = mock(StorageBlobRepository.class);
    when(blobRepository.getImageModelView(sourceBlobId, ImageInputTier.P720))
        .thenReturn(null)
        .thenReturn(
            new StorageBlobRepository.ImageModelView(
                sourceBlobId, ImageInputTier.P720, viewBlobId, 4000, 2000, 1280, 640));
    when(blobRepository.insertImageModelView(any())).thenReturn(true);

    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlob modelViewBlob = mock(StorageBlob.class);
    when(modelViewBlob.getMediaType()).thenReturn("image/png");
    when(modelViewBlob.getSizeBytes()).thenReturn(1234L);
    when(blobManager.getBlob(viewBlobId)).thenReturn(modelViewBlob);

    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    doNothing().when(transactionManager).commit(any(TransactionStatus.class));

    ProviderImageModelViewService service =
        new ProviderImageModelViewService(
            contentService, blobManager, blobRepository, uploadService, transactionManager);

    ProviderImageModelViewService.ModelView first =
        service.resolve(sourceBlobId, "image/png", sourceBytes.length, ImageInputTier.P720);
    ProviderImageModelViewService.ModelView second =
        service.resolve(sourceBlobId, "image/png", sourceBytes.length, ImageInputTier.P720);

    assertEquals(viewBlobId, first.blobId());
    assertEquals(4000, first.originalWidth());
    assertEquals(2000, first.originalHeight());
    assertEquals(1280, first.displayWidth());
    assertEquals(640, first.displayHeight());
    assertEquals(first, second);
    ArgumentCaptor<StorageBlobRepository.ImageModelView> viewCaptor =
        ArgumentCaptor.forClass(StorageBlobRepository.ImageModelView.class);
    verify(blobRepository).insertImageModelView(viewCaptor.capture());
    assertEquals(viewBlobId, viewCaptor.getValue().viewBlobId());
    assertEquals(4000, viewCaptor.getValue().originalWidth());
    assertEquals(1280, viewCaptor.getValue().displayWidth());
    InOrder order = inOrder(blobManager, uploadService);
    order.verify(blobManager).retain(viewBlobId);
    order.verify(uploadService).delete(uploadId);
    verify(uploadService).stage(anyString(), eq("image/png"), any(InputStream.class), anyLong());
  }

  @Test
  void originalWithinTierReturnsOriginalWithoutCreatingAStoredView() {
    UUID sourceBlobId = UUID.randomUUID();
    byte[] sourceBytes = TestImages.png(320, 200);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(contentService.readBlobContent(
            sourceBlobId, ProviderResourceMaterializer.maxInlineBlobBytes()))
        .thenReturn(
            new StorageBlobContent(sourceBlobId, sourceBytes, "image/png", sourceBytes.length));
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobRepository blobRepository = mock(StorageBlobRepository.class);
    StorageUploadService uploadService = mock(StorageUploadService.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    ProviderImageModelViewService service =
        new ProviderImageModelViewService(
            contentService, blobManager, blobRepository, uploadService, transactionManager);

    ProviderImageModelViewService.ModelView result =
        service.resolve(sourceBlobId, "image/png", sourceBytes.length, ImageInputTier.P720);

    assertEquals(sourceBlobId, result.blobId());
    assertEquals(320, result.originalWidth());
    assertEquals(200, result.originalHeight());
    assertEquals(320, result.displayWidth());
    assertEquals(200, result.displayHeight());
    verify(uploadService, never())
        .stage(anyString(), eq("image/png"), any(InputStream.class), anyLong());
    verify(blobRepository, never()).insertImageModelView(any());
  }
}
