package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.platform.storage.S3ObjectContent;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Provider attempt 的 Resource 物化契约：图片内联、其它媒体签名，模态不匹配时确定性文本回退。 */
class ProviderResourceMaterializerTest {

  private static final UUID BLOB_ID = new UUID(0L, 1L);
  private static final ProviderResourceBlock RESOURCE =
      new ProviderResourceBlock(BLOB_ID, "scan.png", "tiny preview");

  @Test
  void imageResourceWithImageModalityProducesInlineDataUri() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", 42L));
    when(storageService.download(
            StorageObjectKeys.blobOriginal(BLOB_ID),
            ProviderResourceMaterializer.MAX_INLINE_IMAGE_BYTES))
        .thenReturn(new S3ObjectContent(new byte[] {0, 1, 2}, "image/png"));

    ProviderResourceMaterializer materializer =
        ProviderResourceMaterializer.withStorage(blobManager, storageService);
    List<ProviderMessage> materialized =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
            Set.of(ModelInputModality.IMAGE));

    ProviderImageBlock image =
        assertInstanceOf(ProviderImageBlock.class, materialized.get(0).contents().get(0));
    assertEquals("image/png", image.mediaType());
    assertEquals("data:image/png;base64,AAEC", image.source());
    verify(storageService)
        .download(
            StorageObjectKeys.blobOriginal(BLOB_ID),
            ProviderResourceMaterializer.MAX_INLINE_IMAGE_BYTES);
    verify(blobManager, never()).presignOriginalUrl(BLOB_ID);
  }

  @Test
  void audioAndVideoModalitiesMatchTheirMediaTypes() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("audio/mpeg", 7L));
    when(blobManager.presignOriginalUrl(BLOB_ID)).thenReturn(url("https://cdn.example.com/audio"));
    ProviderResourceMaterializer audioMaterializer =
        ProviderResourceMaterializer.withStorage(blobManager, storageService);
    List<ProviderMessage> audio =
        audioMaterializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
            Set.of(ModelInputModality.AUDIO));
    assertInstanceOf(ProviderAudioBlock.class, audio.get(0).contents().get(0));

    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("video/mp4", 7L));
    when(blobManager.presignOriginalUrl(BLOB_ID)).thenReturn(url("https://cdn.example.com/video"));
    List<ProviderMessage> video =
        ProviderResourceMaterializer.withStorage(blobManager, storageService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
                Set.of(ModelInputModality.VIDEO));
    assertInstanceOf(ProviderVideoBlock.class, video.get(0).contents().get(0));
  }

  @Test
  void oversizedImageIsRejectedBeforeStorageDownload() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID))
        .thenReturn(
            activeBlob("image/png", ProviderResourceMaterializer.MAX_INLINE_IMAGE_BYTES + 1));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderResourceMaterializer.withStorage(blobManager, storageService)
                    .materialize(
                        List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
                        Set.of(ModelInputModality.IMAGE)));

    assertTrue(error.getMessage().contains("must not exceed"), error.getMessage());
    verify(storageService, never())
        .download(
            StorageObjectKeys.blobOriginal(BLOB_ID),
            ProviderResourceMaterializer.MAX_INLINE_IMAGE_BYTES);
    verify(blobManager, never()).presignOriginalUrl(BLOB_ID);
  }

  @Test
  void imageWithoutImageModalityFallsBackToTextWithStorageFacts() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", 42L));
    List<ProviderMessage> materialized =
        ProviderResourceMaterializer.withStorage(blobManager, storageService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
                Set.of(ModelInputModality.TEXT));

    ProviderTextBlock text =
        assertInstanceOf(ProviderTextBlock.class, materialized.get(0).contents().get(0));
    String fallback = text.text();
    assertTrue(fallback.contains("[Resource: scan.png]"), fallback);
    assertTrue(fallback.contains("blobId: " + BLOB_ID), fallback);
    assertTrue(fallback.contains("mediaType: image/png"), fallback);
    assertTrue(fallback.contains("size: 42"), fallback);
    assertTrue(fallback.contains("preview: tiny preview"), fallback);
    verify(blobManager, never()).presignOriginalUrl(BLOB_ID);
  }

  @Test
  void documentModalityNeverProducesMediaBlock() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", 42L));
    List<ProviderMessage> materialized =
        ProviderResourceMaterializer.withStorage(blobManager, storageService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
                Set.of(ModelInputModality.DOCUMENT));
    assertInstanceOf(
        ProviderTextBlock.class,
        materialized.get(0).contents().get(0),
        "DOCUMENT modality must stay unsupported: no media block, only text fallback");
    verify(blobManager, never()).presignOriginalUrl(BLOB_ID);
  }

  @Test
  void missingOrDeletingBlobFallsBackWithoutMediaFacts() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(null);
    ProviderResourceMaterializer materializer =
        ProviderResourceMaterializer.withStorage(blobManager, storageService);

    List<ProviderMessage> missing =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
            Set.of(ModelInputModality.IMAGE));
    String missingText = ((ProviderTextBlock) missing.get(0).contents().get(0)).text();
    assertTrue(missingText.contains("[Resource: scan.png]"), missingText);
    assertTrue(
        !missingText.contains("mediaType:"), "no storage facts when blob is gone: " + missingText);
    // durable preview 即使 blob 缺失也始终附带。
    assertTrue(missingText.contains("preview: tiny preview"), missingText);
    verify(blobManager, never()).presignOriginalUrl(BLOB_ID);

    StorageBlob deleting = activeBlob("image/png", 42L);
    deleting.setState(StorageBlobState.DELETING);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(deleting);
    List<ProviderMessage> deletingMessages =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
            Set.of(ModelInputModality.IMAGE));
    String deletingText = ((ProviderTextBlock) deletingMessages.get(0).contents().get(0)).text();
    assertTrue(!deletingText.contains("mediaType:"), deletingText);
    assertTrue(deletingText.contains("preview: tiny preview"), deletingText);
    verify(blobManager, never()).presignOriginalUrl(BLOB_ID);
  }

  @Test
  void withoutStorageDegradesAllResourceBlocksToText() {
    ProviderResourceMaterializer materializer = ProviderResourceMaterializer.withoutStorage();
    List<ProviderMessage> materialized =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(RESOURCE))),
            Set.of(ModelInputModality.IMAGE));

    ProviderTextBlock text =
        assertInstanceOf(ProviderTextBlock.class, materialized.get(0).contents().get(0));
    assertTrue(text.text().contains("[Resource: scan.png]"), text.text());
    assertTrue(text.text().contains("blobId: " + BLOB_ID), text.text());
    assertTrue(
        !text.text().contains("mediaType:"), "no storage facts without storage: " + text.text());
    assertTrue(!text.text().contains("https://"), "no URLs without storage: " + text.text());
    assertTrue(
        text.text().contains("preview: tiny preview"),
        "durable preview must survive without storage: " + text.text());
  }

  @Test
  void nonResourceBlocksPassThroughUnchanged() {
    ProviderTextBlock original = new ProviderTextBlock("plain text");
    ProviderResourceMaterializer materializer = ProviderResourceMaterializer.withoutStorage();
    List<ProviderMessage> materialized =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(original))), Set.of());
    assertSame(original, materialized.get(0).contents().get(0));
  }

  @Test
  void nestedTextResourceReconstructsToolResultWithMetadataAndOrder() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    ProviderResourceBlock resource = new ProviderResourceBlock(BLOB_ID, "scan.txt", "tiny preview");
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("text/plain", 42L));
    ProviderTextBlock before = new ProviderTextBlock("before");
    ProviderTextBlock after = new ProviderTextBlock("after");
    ProviderToolResultBlock original =
        new ProviderToolResultBlock(
            "call-text", "read", List.of(before, resource, after), true, "{\"source\":\"test\"}");

    // 意图：覆盖 durable TOOL result 的嵌套 Resource，确认 TEXT 回退确定且不改变结果元数据或兄弟顺序。
    ProviderMessage materializedMessage =
        ProviderResourceMaterializer.withStorage(blobManager, storageService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(original))),
                Set.of(ModelInputModality.TEXT))
            .get(0);

    ProviderToolResultBlock materialized =
        assertInstanceOf(ProviderToolResultBlock.class, materializedMessage.contents().get(0));
    assertNotSame(original, materialized);
    assertEquals("call-text", materialized.toolCallId());
    assertEquals("read", materialized.toolName());
    assertTrue(materialized.error());
    assertEquals("{\"source\":\"test\"}", materialized.detailsJson());
    assertSame(before, materialized.contents().get(0));
    ProviderTextBlock fallback =
        assertInstanceOf(ProviderTextBlock.class, materialized.contents().get(1));
    assertEquals(
        "[Resource: scan.txt]\n"
            + "blobId: 00000000-0000-0000-0000-000000000001\n"
            + "mediaType: text/plain\n"
            + "size: 42\n"
            + "preview: tiny preview",
        fallback.text());
    assertSame(after, materialized.contents().get(2));
  }

  @Test
  void nestedImageResourceProducesImageBlockInsideReconstructedToolResult() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", 42L));
    when(storageService.download(
            StorageObjectKeys.blobOriginal(BLOB_ID),
            ProviderResourceMaterializer.MAX_INLINE_IMAGE_BYTES))
        .thenReturn(new S3ObjectContent(new byte[] {0, 1, 2}, "image/png"));
    ProviderToolResultBlock original =
        new ProviderToolResultBlock(
            "call-image", "read", List.of(RESOURCE), false, "{\"bytes\":3}");

    // 意图：确认嵌套 Resource 仍按 attempt 的 IMAGE 模态读取并替换为 ProviderImageBlock。
    ProviderToolResultBlock materialized =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            ProviderResourceMaterializer.withStorage(blobManager, storageService)
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(original))),
                    Set.of(ModelInputModality.IMAGE))
                .get(0)
                .contents()
                .get(0));

    assertNotSame(original, materialized);
    ProviderImageBlock image =
        assertInstanceOf(ProviderImageBlock.class, materialized.contents().get(0));
    assertEquals("image/png", image.mediaType());
    assertEquals("data:image/png;base64,AAEC", image.source());
  }

  @Test
  void toolResultWithoutResourcesRetainsIdentityAndNestedBlockOrder() {
    ProviderTextBlock first = new ProviderTextBlock("first");
    ProviderTextBlock second = new ProviderTextBlock("second");
    ProviderToolResultBlock original =
        new ProviderToolResultBlock(
            "call-plain", "read", List.of(first, second), false, "{\"ok\":true}");

    // 意图：无 Resource 时不重建 tool result，并保持其嵌套非资源 block 的 identity 与顺序。
    ProviderToolResultBlock materialized =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            ProviderResourceMaterializer.withoutStorage()
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(original))),
                    Set.of())
                .get(0)
                .contents()
                .get(0));

    assertSame(original, materialized);
    assertSame(first, materialized.contents().get(0));
    assertSame(second, materialized.contents().get(1));
  }

  @Test
  void textArtifactAlwaysProducesModelNoticeWithoutStorageAccess() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3StorageService storageService = mock(S3StorageService.class);
    ProviderResourceMaterializer materializer =
        ProviderResourceMaterializer.withStorage(blobManager, storageService);

    String artifactPath =
        "/.artifacts/tool-results/00000000-0000-0000-0000-000000000010/00000000-0000-0000-0000-000000000020.txt";
    ProviderResourceBlock textArtifact =
        new ProviderResourceBlock(
            BLOB_ID, "demo.txt", artifactPath, 12345L, 100L, "first 20 lines");

    List<ProviderMessage> result =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(textArtifact))),
            Set.of(ModelInputModality.IMAGE, ModelInputModality.TEXT));

    ProviderTextBlock text =
        assertInstanceOf(ProviderTextBlock.class, result.get(0).contents().get(0));
    String notice = text.text();
    assertTrue(notice.contains("Full output: " + artifactPath), notice);
    assertTrue(notice.contains("Size: 12345 bytes, 100 lines"), notice);
    assertTrue(notice.contains("--- preview ---"), notice);
    assertTrue(notice.contains("first 20 lines"), notice);

    // 绝不查询 blob 或调用存储服务
    verify(blobManager, never()).getBlob(BLOB_ID);
    verify(storageService, never()).download(any(), anyLong());
  }

  private static StorageBlob activeBlob(String mediaType, long sizeBytes) {
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_ID);
    blob.setMediaType(mediaType);
    blob.setSizeBytes(sizeBytes);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }

  private static StoragePresignedUrlDTO url(String value) {
    return StoragePresignedUrlDTO.builder().method("GET").url(value).build();
  }
}
