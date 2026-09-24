package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

/** History 物化只消费 READY upload 的数据库事实，不执行资源读取或对象上传；图片工具结果在这里冻结平台默认输入档位（720P），非图片媒体不携带档位。 */
class GlobalStorageToolResultHistoryMaterializerTest {

  private static final String SHA256 = "a".repeat(64);

  @Test
  void consumesStagedTextAndPreservesValidatedMetadata() {
    // READY upload 的 owner 在同一调用中转移给 session，文本 metadata 原样进入 durable 内容。
    UUID sessionId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    String sha256 = "a".repeat(64);
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    when(uploadService.lockReady(uploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(blobId, "result.txt"));
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setMediaType("text/plain");
    blob.setSizeBytes(7);
    blob.setSha256(sha256);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    GlobalStorageToolResultHistoryMaterializer materializer =
        new GlobalStorageToolResultHistoryMaterializer(
            uploadService, blobManager, refManager, 1024);
    ResourceResultContent staged =
        new ResourceResultContent(
            new ResourceRef(
                ResourceRef.blobUploadUri(uploadId), "text/plain", "untrusted.txt", 7L, sha256),
            "preview",
            new TextArtifactMetadata(7, 1));

    ResourceMessageContent content =
        assertInstanceOf(
            ResourceMessageContent.class,
            materializer
                .materialize(
                    sessionId, "tool", new ToolResult("call", List.of(staged), false, "{}"))
                .getFirst());

    assertEquals(blobId, content.blobId());
    assertEquals("result.txt", content.name());
    assertEquals(7L, content.totalBytes());
    assertEquals(1L, content.totalLines());
    assertEquals("preview", content.preview());
    verify(refManager).retainRef(sessionId, blobId);
    verify(uploadService).delete(uploadId);
  }

  /** 意图：工具结果图片没有用户选择，必须在 durable 历史里冻结平台默认档位 720P。 */
  @Test
  void imageResultFreezesPlatformDefaultTier() {
    UUID sessionId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    ResourceMessageContent content =
        materialize(sessionId, uploadId, blobId, "image/png", "scan.png", 9L, "preview", null);

    assertEquals(blobId, content.blobId());
    assertEquals(ImageInputTier.P720, content.imageTier());
    // 图片不是文本工件：不携带 totalBytes / totalLines。
    assertNull(content.totalBytes());
    assertNull(content.totalLines());
  }

  /** 意图：非图片媒体不携带图片输入档位（音频/视频/PDF 与文本工件一样没有档位可冻结）。 */
  @Test
  void nonImageResultCarriesNoImageTier() {
    UUID sessionId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    assertNull(
        materialize(sessionId, uploadId, blobId, "video/mp4", "clip.mp4", 9L, "preview", null)
            .imageTier());
    assertNull(
        materialize(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "application/pdf",
                "doc.pdf",
                9L,
                "preview",
                null)
            .imageTier());
  }

  private static ResourceMessageContent materialize(
      UUID sessionId,
      UUID uploadId,
      UUID blobId,
      String mediaType,
      String filename,
      long sizeBytes,
      String preview,
      TextArtifactMetadata metadata) {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    when(uploadService.lockReady(uploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(blobId, filename));
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setMediaType(mediaType);
    blob.setSizeBytes(sizeBytes);
    blob.setSha256(SHA256);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    GlobalStorageToolResultHistoryMaterializer materializer =
        new GlobalStorageToolResultHistoryMaterializer(
            uploadService, blobManager, refManager, 1024);
    ResourceResultContent staged =
        new ResourceResultContent(
            new ResourceRef(
                ResourceRef.blobUploadUri(uploadId), mediaType, "untrusted.txt", sizeBytes, SHA256),
            preview,
            metadata);

    return assertInstanceOf(
        ResourceMessageContent.class,
        materializer
            .materialize(sessionId, "tool", new ToolResult("call", List.of(staged), false, "{}"))
            .getFirst());
  }
}
