package fun.fengwk.kkstudio.platform.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

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

/** Platform BlobAccess 必须冻结最小 facts，并保留 original 长度、关闭与预签名语义；强依赖 S3 与 BlobManager。 */
class PlatformCanvasFunctionBlobAccessTest {

  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void mapsFactsAndDelegatesPresignWithoutLeakingPlatformTypes() {
    // 测试意图：验证通过强依赖注入的 S3StorageService 和 StorageBlobManager 正确映射 facts 与预签名。
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
        new PlatformCanvasFunctionBlobAccess(mock(S3StorageService.class), manager);

    assertEquals(
        new BlobFacts(BLOB, "image/png", 3L, 1L, 2L, null), access.findFacts(BLOB).orElseThrow());
    assertEquals("https://s3.example/original", access.originalUrl(BLOB, 120L));
    verify(manager).presignOriginalUrl(BLOB);
  }

  @Test
  void closesMismatchedOriginalBeforeRejectingIt() {
    // 测试意图：验证 S3 对象长度与预期不一致时，流在报错前被正确关闭。
    S3StorageService storage = mock(S3StorageService.class);
    TrackingInputStream content = new TrackingInputStream(new byte[] {1, 2});
    when(storage.readObject(StorageObjectKeys.blobOriginal(BLOB)))
        .thenReturn(new S3ObjectStream(content, new S3ObjectMetadata(2L, "image/png", null)));
    PlatformCanvasFunctionBlobAccess access =
        new PlatformCanvasFunctionBlobAccess(storage, mock(StorageBlobManager.class));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> access.openOriginal(BLOB, 3L));

    assertEquals("S3 original length does not match frozen blob size", error.getMessage());
    assertTrue(content.closed);
  }

  @Test
  void rejectsNullDependenciesInConstructorAndOpensClosableOriginal() throws Exception {
    // 测试意图：验证构造器强依赖注入非空拒绝，以及正常打开可关闭的 S3 原件流。
    S3StorageService storage = mock(S3StorageService.class);
    StorageBlobManager manager = mock(StorageBlobManager.class);

    assertThrows(
        NullPointerException.class, () -> new PlatformCanvasFunctionBlobAccess(null, manager));
    assertThrows(
        NullPointerException.class, () -> new PlatformCanvasFunctionBlobAccess(storage, null));

    ByteArrayInputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    when(storage.readObject(StorageObjectKeys.blobOriginal(BLOB)))
        .thenReturn(new S3ObjectStream(content, new S3ObjectMetadata(3L, "image/png", null)));
    PlatformCanvasFunctionBlobAccess access =
        new PlatformCanvasFunctionBlobAccess(storage, manager);

    try (CanvasFunctionResourceStream stream = access.openOriginal(BLOB, 3L)) {
      assertEquals(3L, stream.size());
      assertEquals(1, stream.content().read());
    }
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
