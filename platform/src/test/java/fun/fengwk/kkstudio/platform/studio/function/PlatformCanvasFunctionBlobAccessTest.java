package fun.fengwk.kkstudio.platform.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess.BlobFacts;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/** Platform BlobAccess 必须冻结最小 facts，并保留 original 长度、关闭与预签名语义。 */
class PlatformCanvasFunctionBlobAccessTest {

  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void mapsFactsAndDelegatesPresignWithoutLeakingPlatformTypes() {
    StorageBlobManager manager = mock(StorageBlobManager.class);
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB);
    blob.setMediaType("image/png");
    blob.setSizeBytes(3L);
    blob.setWidth(1L);
    blob.setHeight(2L);
    when(manager.getBlob(BLOB)).thenReturn(blob);
    when(manager.presignOriginalUrl(BLOB))
        .thenReturn(StoragePresignedUrlDTO.builder().url("https://s3.example/original").build());
    PlatformCanvasFunctionBlobAccess access =
        new PlatformCanvasFunctionBlobAccess(
            provider(mock(S3StorageService.class)), provider(manager));

    assertEquals(
        new BlobFacts(BLOB, "image/png", 3L, 1L, 2L, null), access.findFacts(BLOB).orElseThrow());
    assertEquals("https://s3.example/original", access.originalUrl(BLOB, 120L));
    verify(manager).presignOriginalUrl(BLOB);
  }

  @Test
  void closesMismatchedOriginalBeforeRejectingIt() {
    S3StorageService storage = mock(S3StorageService.class);
    TrackingInputStream content = new TrackingInputStream(new byte[] {1, 2});
    when(storage.readObject(StorageObjectKeys.blobOriginal(BLOB)))
        .thenReturn(new S3ObjectStream(content, new S3ObjectMetadata(2L, "image/png", null)));
    PlatformCanvasFunctionBlobAccess access =
        new PlatformCanvasFunctionBlobAccess(
            storageProvider(storage), provider(mock(StorageBlobManager.class)));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> access.openOriginal(BLOB, 3L));

    assertEquals("S3 original length does not match frozen blob size", error.getMessage());
    assertTrue(content.closed);
  }

  @Test
  void returnsClosableOriginalAndFailsWithStableUnavailableMessages() throws Exception {
    S3StorageService storage = mock(S3StorageService.class);
    ByteArrayInputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    when(storage.readObject(StorageObjectKeys.blobOriginal(BLOB)))
        .thenReturn(new S3ObjectStream(content, new S3ObjectMetadata(3L, "image/png", null)));
    PlatformCanvasFunctionBlobAccess access =
        new PlatformCanvasFunctionBlobAccess(storageProvider(storage), provider(null));

    try (CanvasFunctionResourceStream stream = access.openOriginal(BLOB, 3L)) {
      assertEquals(3L, stream.size());
      assertEquals(1, stream.content().read());
    }
    IllegalStateException factsError =
        assertThrows(IllegalStateException.class, () -> access.findFacts(BLOB));
    assertEquals("global blob storage is unavailable", factsError.getMessage());
    NullPointerException urlError =
        assertThrows(NullPointerException.class, () -> access.originalUrl(BLOB, 120L));
    assertEquals(
        "StorageBlobManager is required for Canvas Function runtime", urlError.getMessage());
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private static ObjectProvider<S3StorageService> storageProvider(S3StorageService storage) {
    return provider(storage);
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    private TrackingInputStream(byte[] buffer) {
      super(buffer);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
