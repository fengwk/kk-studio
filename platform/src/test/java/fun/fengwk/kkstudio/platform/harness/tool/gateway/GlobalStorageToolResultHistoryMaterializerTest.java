package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

  /** 意图：平台 read 已授权的 Session Blob 作为 durable 工具结果媒体复用，不重新上传、不读对象存储，图片冻结默认档位。 */
  @Test
  void reusesAuthorizedSessionBlobWithoutUpload() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    stubBlob(blobManager, blobId, "image/png", 9L);
    when(refManager.contains(sessionId, blobId)).thenReturn(true);
    GlobalStorageToolResultHistoryMaterializer materializer =
        materializer(uploadService, blobManager, refManager);
    ResourceResultContent sessionResource = sessionResource(blobId, "image/png", 9L, "preview");

    ResourceMessageContent content =
        assertInstanceOf(
            ResourceMessageContent.class,
            materializer
                .materialize(
                    sessionId,
                    "read",
                    new ToolResult("call", List.of(sessionResource), false, "{}"))
                .getFirst());

    assertEquals(blobId, content.blobId());
    assertEquals(blobId.toString(), content.name());
    assertEquals(ImageInputTier.P720, content.imageTier());
    assertNull(content.totalBytes());
    verify(refManager).retainRef(sessionId, blobId);
    // 复用不产生任何上传副作用：既不锁定、也不删除上传行。
    verifyNoInteractions(uploadService);
  }

  /**
   * 意图：kkstudio 引用只可复用当前 Session 已有的授权；合法且 ACTIVE 的 Blob 在 Session 未引用时也必须拒绝，且必须在读取任何元数据、 retainRef
   * 或资源写入之前失败（不可区分错误）。
   */
  @Test
  void rejectsSessionResourceWithoutExistingAuthorization() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    stubBlob(blobManager, blobId, "image/png", 9L);
    when(refManager.contains(sessionId, blobId)).thenReturn(false);
    GlobalStorageToolResultHistoryMaterializer materializer =
        materializer(uploadService, blobManager, refManager);
    ResourceResultContent sessionResource = sessionResource(blobId, "image/png", 9L, "preview");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                materializer.materialize(
                    sessionId,
                    "read",
                    new ToolResult("call", List.of(sessionResource), false, "{}")));

    assertEquals("session resource is not referenced by this session", error.getMessage());
    verify(blobManager, never()).getBlob(any());
    verify(refManager, never()).retainRef(any(), any());
    verifyNoInteractions(uploadService);
  }

  /** 意图：ref 声明的 MIME 与权威 blob 不一致（事实被篡改）时拒绝，不产生 history 也不 retain。 */
  @Test
  void rejectsSessionResourceWithMismatchedMetadata() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    stubBlob(blobManager, blobId, "audio/mpeg", 9L);
    when(refManager.contains(sessionId, blobId)).thenReturn(true);
    GlobalStorageToolResultHistoryMaterializer materializer =
        materializer(uploadService, blobManager, refManager);
    ResourceResultContent sessionResource = sessionResource(blobId, "image/png", 9L, "preview");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                materializer.materialize(
                    sessionId,
                    "read",
                    new ToolResult("call", List.of(sessionResource), false, "{}")));
    assertEquals("session resource failed integrity validation", error.getMessage());
    verify(refManager, never()).retainRef(any(), any());
    verifyNoInteractions(uploadService);
  }

  /** 意图：Session 资源只接受可送达模型的媒体类型；任意其它文件（如 CSV）一律拒绝。 */
  @Test
  void rejectsSessionResourceOfNonModelMediaType() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    stubBlob(blobManager, blobId, "text/csv", 9L);
    when(refManager.contains(sessionId, blobId)).thenReturn(true);
    GlobalStorageToolResultHistoryMaterializer materializer =
        materializer(uploadService, blobManager, refManager);
    ResourceResultContent sessionResource = sessionResource(blobId, "text/csv", 9L, "preview");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                materializer.materialize(
                    sessionId,
                    "read",
                    new ToolResult("call", List.of(sessionResource), false, "{}")));
    assertEquals("session resource is not a supported model media type", error.getMessage());
    verify(refManager, never()).retainRef(any(), any());
    verifyNoInteractions(uploadService);
  }

  private static GlobalStorageToolResultHistoryMaterializer materializer(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      SessionBlobRefManager refManager) {
    return new GlobalStorageToolResultHistoryMaterializer(
        uploadService, blobManager, refManager, 1024);
  }

  private static void stubBlob(
      StorageBlobManager blobManager, UUID blobId, String mediaType, long sizeBytes) {
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setMediaType(mediaType);
    blob.setSizeBytes(sizeBytes);
    blob.setSha256(SHA256);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
  }

  private static ResourceResultContent sessionResource(
      UUID blobId, String mediaType, long sizeBytes, String preview) {
    return new ResourceResultContent(
        new ResourceRef(
            ResourceRef.sessionResourceUri(blobId),
            mediaType,
            blobId.toString(),
            sizeBytes,
            SHA256),
        preview,
        null);
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
