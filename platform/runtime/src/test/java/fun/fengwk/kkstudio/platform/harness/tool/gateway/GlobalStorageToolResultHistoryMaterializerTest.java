package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.runtime.port.ToolOutcomeMaterializationException;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

/** 物化只消费 READY upload 的数据库事实并返回稳定 ToolResult，不执行资源读取、对象上传或历史映射。 */
class GlobalStorageToolResultHistoryMaterializerTest {

  private static final String SHA256 = "a".repeat(64);

  @Test
  void consumesStagedTextAndReturnsStableResource() {
    UUID sessionId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    when(uploadService.lockReady(uploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(blobId, "result.txt"));
    stubBlob(blobManager, blobId, "text/plain", 7L);
    GlobalStorageToolResultHistoryMaterializer materializer =
        materializer(uploadService, blobManager, refManager);
    ResourceResultContent staged =
        new ResourceResultContent(
            new ResourceRef(
                ResourceRef.blobUploadUri(uploadId), "text/plain", "untrusted.txt", 7L, SHA256),
            "preview",
            new TextArtifactMetadata(7, 1));

    ToolResult stable =
        materializer.materialize(
            sessionId, "tool", new ToolResult("call", List.of(staged), false, "{}"));
    ResourceResultContent content =
        assertInstanceOf(ResourceResultContent.class, stable.contents().getFirst());

    assertEquals(blobId, content.resource().sessionBlobId());
    assertEquals("result.txt", content.resource().name());
    assertEquals(7L, content.resource().size());
    assertEquals(SHA256, content.resource().sha256());
    assertEquals("preview", content.preview());
    assertEquals(new TextArtifactMetadata(7, 1), content.textMetadata());
    assertEquals("call", stable.toolCallId());
    verify(refManager).retainRef(sessionId, blobId);
    verify(uploadService).delete(uploadId);
  }

  /** 意图：平台 read 已授权的 Session Blob 复用为稳定工具结果，不重新上传、不读对象存储。 */
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

    ToolResult stable =
        materializer.materialize(
            sessionId, "read", new ToolResult("call", List.of(sessionResource), false, "{}"));
    ResourceResultContent content =
        assertInstanceOf(ResourceResultContent.class, stable.contents().getFirst());

    assertEquals(blobId, content.resource().sessionBlobId());
    assertEquals(blobId.toString(), content.resource().name());
    assertEquals("preview", content.preview());
    verify(refManager).retainRef(sessionId, blobId);
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

    ToolOutcomeMaterializationException error =
        assertThrows(
            ToolOutcomeMaterializationException.class,
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

  /** 意图：ref 声明的 MIME 与权威 blob 不一致（事实被篡改）时拒绝，不产生稳定结果也不 retain。 */
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

    ToolOutcomeMaterializationException error =
        assertThrows(
            ToolOutcomeMaterializationException.class,
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

    ToolOutcomeMaterializationException error =
        assertThrows(
            ToolOutcomeMaterializationException.class,
            () ->
                materializer.materialize(
                    sessionId,
                    "read",
                    new ToolResult("call", List.of(sessionResource), false, "{}")));
    assertEquals("session resource is not a supported model media type", error.getMessage());
    verify(refManager, never()).retainRef(any(), any());
    verifyNoInteractions(uploadService);
  }

  @Test
  void rejectsUploadWithoutAuthoritativeFilename() {
    UUID sessionId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    when(uploadService.lockReady(uploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(blobId, null));
    stubBlob(blobManager, blobId, "text/plain", 7L);
    GlobalStorageToolResultHistoryMaterializer materializer =
        materializer(uploadService, blobManager, refManager);
    ResourceResultContent staged =
        new ResourceResultContent(
            new ResourceRef(
                ResourceRef.blobUploadUri(uploadId), "text/plain", "untrusted.txt", 7L, SHA256));

    assertThrows(
        ToolOutcomeMaterializationException.class,
        () ->
            materializer.materialize(
                sessionId, "tool", new ToolResult("call", List.of(staged), false, "{}")));

    verify(refManager, never()).retainRef(any(), any());
    verify(uploadService, never()).delete(any());
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
}
