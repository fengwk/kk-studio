package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

/** History 物化只消费 READY upload 的数据库事实，不执行资源读取或对象上传。 */
class GlobalStorageToolResultHistoryMaterializerTest {

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
}
