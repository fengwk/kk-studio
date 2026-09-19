package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider attempt 的 Resource 物化契约：只有用户普通内容与 TOOL 结果内容中的、model 与 adapter 同时支持的媒体才内联为
 * Base64；其余一律确定性文本回退，且任何路径都不产生 URL、不读取未授权内容。
 *
 * <p>测试意图涵盖：四类媒体块与模态/能力交集、DOCUMENT 仅 PDF、SYSTEM/ASSISTANT 与非支持媒体类型的免读取回退、缺失或转 DELETING 的 Blob
 * 每次都重新判定状态（含缓存命中）、声明大小与单次 request 字符总量上限（重复与嵌套引用同样计入）、缓存对读 取的合并，以及 replay state/顺序/嵌套元数据的透传。
 */
class ProviderResourceMaterializerTest {

  private static final UUID BLOB_ID = new UUID(0L, 1L);
  private static final UUID OTHER_BLOB_ID = new UUID(0L, 2L);
  private static final byte[] BYTES = new byte[] {0, 1, 2};
  private static final String IMAGE_DATA_URI = "data:image/png;base64,AAEC";
  private static final ProviderResourceBlock IMAGE_RESOURCE =
      new ProviderResourceBlock(BLOB_ID, "scan.png", "tiny preview");
  private static final ProviderMediaCapabilities ALL_MEDIA =
      new ProviderMediaCapabilities(
          Set.of(
              ModelInputModality.IMAGE,
              ModelInputModality.AUDIO,
              ModelInputModality.VIDEO,
              ModelInputModality.DOCUMENT),
          Set.of(
              ModelInputModality.IMAGE,
              ModelInputModality.AUDIO,
              ModelInputModality.VIDEO,
              ModelInputModality.DOCUMENT));

  // ---------- 支持媒体的内联路径 ----------

  @Test
  void imageResourceWithImageModalityAndCapabilityProducesInlineDataUri() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA);

    ProviderImageBlock image =
        assertInstanceOf(ProviderImageBlock.class, materialized.get(0).contents().get(0));
    assertEquals("image/png", image.mediaType());
    assertEquals(IMAGE_DATA_URI, image.source());
    verify(contentService).readBlobContent(BLOB_ID, defaultReaderLimits().maxBlobBytes());
    // 内联路径绝不触碰预签名能力。
    verify(blobManager, never()).presignOriginalUrl(any());
    verify(blobManager, never()).presignPreviewUrl(any());
  }

  @Test
  void audioAndVideoProduceBase64DataUrisInsteadOfUrls() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("audio/mpeg", BYTES.length));
    stubContent(contentService, BLOB_ID, "audio/mpeg", BYTES);
    ProviderResourceMaterializer materializer = materializer(blobManager, contentService);

    ProviderAudioBlock audio =
        assertInstanceOf(
            ProviderAudioBlock.class,
            materializer
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                    Set.of(ModelInputModality.AUDIO),
                    ALL_MEDIA)
                .get(0)
                .contents()
                .get(0));

    StorageBlobManager videoBlobManager = mock(StorageBlobManager.class);
    StorageBlobContentService videoContentService = mock(StorageBlobContentService.class);
    when(videoBlobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("video/mp4", BYTES.length));
    stubContent(videoContentService, BLOB_ID, "video/mp4", BYTES);
    ProviderVideoBlock video =
        assertInstanceOf(
            ProviderVideoBlock.class,
            materializer(videoBlobManager, videoContentService)
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                    Set.of(ModelInputModality.VIDEO),
                    ALL_MEDIA)
                .get(0)
                .contents()
                .get(0));

    assertTrue(audio.source().startsWith("data:audio/mpeg;base64,"), audio.source());
    assertEquals(
        "data:audio/mpeg;base64," + Base64.getEncoder().encodeToString(BYTES), audio.source());
    assertEquals(
        "data:video/mp4;base64," + Base64.getEncoder().encodeToString(BYTES), video.source());
  }

  @Test
  void pdfResourceProducesDocumentBlockOnlyForApplicationPdf() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("application/pdf", BYTES.length));
    stubContent(contentService, BLOB_ID, "application/pdf", BYTES);

    ProviderDocumentBlock document =
        assertInstanceOf(
            ProviderDocumentBlock.class,
            materializer(blobManager, contentService)
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                    Set.of(ModelInputModality.DOCUMENT),
                    ALL_MEDIA)
                .get(0)
                .contents()
                .get(0));

    assertEquals("application/pdf", document.mediaType());
    assertEquals("data:application/pdf;base64,AAEC", document.source());
  }

  @Test
  void nonPdfDocumentNeverFabricatesDocumentBlock() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("application/msword", BYTES.length));

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.DOCUMENT),
                ALL_MEDIA);

    ProviderTextBlock fallback =
        assertInstanceOf(ProviderTextBlock.class, materialized.get(0).contents().get(0));
    assertTrue(fallback.text().contains("mediaType: application/msword"), fallback.text());
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void toolResultContentsUseToolResultCapabilitiesNotUserCapabilities() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);
    ProviderToolResultBlock toolResult =
        new ProviderToolResultBlock(
            "call-image", "read", List.of(IMAGE_RESOURCE), false, "{\"bytes\":3}");
    ProviderMediaCapabilities userOnly =
        new ProviderMediaCapabilities(Set.of(ModelInputModality.IMAGE), Set.of());
    ProviderMediaCapabilities toolOnly =
        new ProviderMediaCapabilities(Set.of(), Set.of(ModelInputModality.IMAGE));

    // 能力只声明在用户位置：TOOL 内容必须回退，绝不借用用户能力读取内容。
    ProviderToolResultBlock degraded =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            materializer(blobManager, contentService)
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(toolResult))),
                    Set.of(ModelInputModality.IMAGE),
                    userOnly)
                .get(0)
                .contents()
                .get(0));
    assertInstanceOf(ProviderTextBlock.class, degraded.contents().get(0));
    verify(contentService, never()).readBlobContent(any(), anyLong());

    // 能力只声明在工具结果位置：TOOL 内容正常内联，用户普通内容仍回退。
    ProviderToolResultBlock inlined =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            materializer(blobManager, contentService)
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(toolResult))),
                    Set.of(ModelInputModality.IMAGE),
                    toolOnly)
                .get(0)
                .contents()
                .get(0));
    ProviderImageBlock image =
        assertInstanceOf(ProviderImageBlock.class, inlined.contents().get(0));
    assertEquals(IMAGE_DATA_URI, image.source());
    List<ProviderMessage> userMessage =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                toolOnly);
    assertInstanceOf(ProviderTextBlock.class, userMessage.get(0).contents().get(0));
  }

  @Test
  void noCapabilitiesFallBackToTextWithoutReading() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ProviderMediaCapabilities.NONE);

    ProviderTextBlock fallback =
        assertInstanceOf(ProviderTextBlock.class, materialized.get(0).contents().get(0));
    assertTrue(fallback.text().contains("[Resource: scan.png]"), fallback.text());
    assertTrue(fallback.text().contains("mediaType: image/png"), fallback.text());
    assertTrue(fallback.text().contains("size: 3"), fallback.text());
    assertTrue(fallback.text().contains("preview: tiny preview"), fallback.text());
    verify(contentService, never()).readBlobContent(any(), anyLong());
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  @Test
  void unsupportedModelModalityFallsBackToTextWithStorageFacts() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.TEXT),
                ALL_MEDIA);

    ProviderTextBlock text =
        assertInstanceOf(ProviderTextBlock.class, materialized.get(0).contents().get(0));
    assertTrue(text.text().contains("blobId: " + BLOB_ID), text.text());
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  // ---------- 角色边界 ----------

  /** 意图：媒体内联只允许 USER 与 native TOOL 结果两个位置；其他角色（如 ASSISTANT）的 Resource 一律文本回退， 绝不读取内容或产生预签名 URL。 */
  @ParameterizedTest
  @EnumSource(
      value = ProviderMessageRole.class,
      names = {"ASSISTANT"})
  void assistantResourcesAlwaysFallBackToText(ProviderMessageRole role) {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(role, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA);

    ProviderTextBlock fallback =
        assertInstanceOf(ProviderTextBlock.class, materialized.get(0).contents().get(0));
    assertTrue(fallback.text().contains("[Resource: scan.png]"), fallback.text());
    verify(contentService, never()).readBlobContent(any(), anyLong());
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  /** 意图：消息契约在进入物化器之前就拒绝错位的工具内容。 */
  @Test
  void malformedMediaPositionsAreRejectedByMessageContract() {
    ProviderToolResultBlock nested =
        new ProviderToolResultBlock("call", "read", List.of(IMAGE_RESOURCE), false, "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderMessage(ProviderMessageRole.TOOL, List.of(IMAGE_RESOURCE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderMessage(ProviderMessageRole.USER, List.of(nested)));
  }

  // ---------- Blob 事实 ----------

  @Test
  void missingOrDeletingBlobFallsBackWithoutMediaFacts() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(null);
    ProviderResourceMaterializer materializer = materializer(blobManager, contentService);

    List<ProviderMessage> missing =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
            Set.of(ModelInputModality.IMAGE),
            ALL_MEDIA);
    String missingText = ((ProviderTextBlock) missing.get(0).contents().get(0)).text();
    assertTrue(missingText.contains("[Resource: scan.png]"), missingText);
    assertFalse(missingText.contains("mediaType:"), missingText);
    assertTrue(missingText.contains("preview: tiny preview"), missingText);

    StorageBlob deleting = activeBlob("image/png", BYTES.length);
    deleting.setState(StorageBlobState.DELETING);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(deleting);
    List<ProviderMessage> deletingMessages =
        materializer.materialize(
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
            Set.of(ModelInputModality.IMAGE),
            ALL_MEDIA);
    String deletingText = ((ProviderTextBlock) deletingMessages.get(0).contents().get(0)).text();
    assertFalse(deletingText.contains("mediaType:"), deletingText);
    verify(contentService, never()).readBlobContent(any(), anyLong());
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  /** 意图：即使缓存已有同一 Blob 的 data URI，每次都重新判定 ACTIVE；转 DELETING 后必须回退且不再读取。 */
  @Test
  void cacheHitStillRequiresActiveMetadataOnEveryAttempt() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);
    ProviderResourceMaterializer materializer = materializer(blobManager, contentService);
    ProviderMessage message =
        new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE));

    assertInstanceOf(
        ProviderImageBlock.class,
        materializer
            .materialize(List.of(message), Set.of(ModelInputModality.IMAGE), ALL_MEDIA)
            .get(0)
            .contents()
            .get(0));

    StorageBlob deleting = activeBlob("image/png", BYTES.length);
    deleting.setState(StorageBlobState.DELETING);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(deleting);

    ProviderTextBlock fallback =
        assertInstanceOf(
            ProviderTextBlock.class,
            materializer
                .materialize(List.of(message), Set.of(ModelInputModality.IMAGE), ALL_MEDIA)
                .get(0)
                .contents()
                .get(0));
    assertFalse(fallback.text().contains("mediaType:"), fallback.text());
    // 仅第一次尝试发生读取：缓存命中路径同样受 ACTIVE 前置判定约束。
    verify(contentService, times(1)).readBlobContent(any(), anyLong());
  }

  @Test
  void repeatedResourceOccurrencesAreReadOncePerAttempt() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE)),
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA);

    assertEquals(
        IMAGE_DATA_URI, ((ProviderImageBlock) materialized.get(0).contents().get(0)).source());
    assertEquals(
        IMAGE_DATA_URI, ((ProviderImageBlock) materialized.get(1).contents().get(0)).source());
    verify(contentService, times(1)).readBlobContent(BLOB_ID, defaultReaderLimits().maxBlobBytes());
  }

  @Test
  void externalizedTextAlwaysProducesModelNoticeWithoutStorageAccess() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    ProviderResourceBlock externalizedText =
        ProviderResourceBlock.externalizedText(BLOB_ID, "demo.txt", 12345L, 100L, "first 20 lines");

    List<ProviderMessage> result =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(externalizedText))),
                Set.of(ModelInputModality.IMAGE, ModelInputModality.TEXT),
                ALL_MEDIA);

    ProviderTextBlock text =
        assertInstanceOf(ProviderTextBlock.class, result.get(0).contents().get(0));
    assertTrue(
        text.text()
            .contains("complete output has been saved as a downloadable user attachment: demo.txt"),
        text.text());
    assertTrue(text.text().contains("Size: 12345 bytes, 100 lines"), text.text());
    verify(blobManager, never()).getBlob(any());
    verify(contentService, never()).readBlobContent(any(), anyLong());
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  // ---------- 大小上限 ----------

  @Test
  void singleFileBeyondReaderLimitFailsExplicitlyBeforeReading() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    long maxBytes = 4L;
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", maxBytes + 1L));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                materializer(blobManager, contentService, limits(maxBytes), Long.MAX_VALUE)
                    .materialize(
                        List.of(
                            new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                        Set.of(ModelInputModality.IMAGE),
                        ALL_MEDIA));

    assertTrue(error.getMessage().contains("allowed inline range"), error.getMessage());
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void declaredSizeAtBoundaryIsAccepted() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);

    ProviderImageBlock image =
        assertInstanceOf(
            ProviderImageBlock.class,
            materializer(blobManager, contentService, limits(BYTES.length), Long.MAX_VALUE)
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                    Set.of(ModelInputModality.IMAGE),
                    ALL_MEDIA)
                .get(0)
                .contents()
                .get(0));

    assertEquals(IMAGE_DATA_URI, image.source());
  }

  @Test
  void actualBytesBeyondDeclaredSizeFailExplicitlyInsteadOfTruncating() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    // 读取到的实际字节数大于权威声明：损坏内容必须显式失败，绝不生成截断的 data URI。
    when(contentService.readBlobContent(BLOB_ID, defaultReaderLimits().maxBlobBytes()))
        .thenReturn(
            new StorageBlobContent(BLOB_ID, new byte[] {0, 1, 2, 3}, "image/png", BYTES.length));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                materializer(blobManager, contentService)
                    .materialize(
                        List.of(
                            new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                        Set.of(ModelInputModality.IMAGE),
                        ALL_MEDIA));

    assertTrue(error.getMessage().contains("length mismatch"), error.getMessage());
  }

  /** 意图：单次 request 的 data URI 字符总量是应用安全上限，重复引用与嵌套 tool 内容同样计入；超出时显式失败而非静默丢弃。 */
  @Test
  void requestInlineBudgetCountsRepeatedAndNestedOccurrences() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);
    long singleDataUriChars =
        ProviderInlineBlobReader.estimatedDataUriChars("image/png", BYTES.length);

    // 预算只够一份 data URI：同一 Resource 的第二次出现（含嵌套 tool 结果）必须显式失败。
    ProviderResourceMaterializer materializer =
        materializer(blobManager, contentService, defaultReaderLimits(), singleDataUriChars);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            materializer.materialize(
                List.of(
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE)),
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA));

    ProviderToolResultBlock nested =
        new ProviderToolResultBlock("call-1", "read", List.of(IMAGE_RESOURCE), false, "{}");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            materializer.materialize(
                List.of(
                    new ProviderMessage(ProviderMessageRole.TOOL, List.of(nested)),
                    new ProviderMessage(ProviderMessageRole.TOOL, List.of(nested))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA));

    // 预算足够时全部出现位置都成功内联，说明预算按每次 attempt 独立计算且不泄漏到后续调用。
    ProviderResourceMaterializer generous =
        materializer(blobManager, contentService, defaultReaderLimits(), 2L * singleDataUriChars);
    assertEquals(
        2,
        generous
            .materialize(
                List.of(
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE)),
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA)
            .size());
  }

  @Test
  void budgetExhaustedByEarlierResourceFailsRatherThanReturningPartialRequest() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    when(blobManager.getBlob(OTHER_BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);
    stubContent(contentService, OTHER_BLOB_ID, "image/png", BYTES);
    long singleDataUriChars =
        ProviderInlineBlobReader.estimatedDataUriChars("image/png", BYTES.length);
    ProviderResourceBlock otherResource =
        new ProviderResourceBlock(OTHER_BLOB_ID, "other.png", "other preview");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                materializer(blobManager, contentService, defaultReaderLimits(), singleDataUriChars)
                    .materialize(
                        List.of(
                            new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE)),
                            new ProviderMessage(ProviderMessageRole.USER, List.of(otherResource))),
                        Set.of(ModelInputModality.IMAGE),
                        ALL_MEDIA));

    assertTrue(error.getMessage().contains("must not exceed"), error.getMessage());
    // 超限的第二个 Blob 绝不触发读取：预算在读取前校验声明大小。
    verify(contentService, never())
        .readBlobContent(OTHER_BLOB_ID, defaultReaderLimits().maxBlobBytes());
  }

  @Test
  void productionRequestInlineBudgetIsAtLeastSingleFileLimitInBase64() {
    // 生产常量必须能容纳单文件原始字节上限编码后的 data URI，否则任何最大文件都会必然失败。
    long singleFileDataUriChars =
        ProviderInlineBlobReader.estimatedDataUriChars(
            "image/png", ProviderInlineBlobReader.Limits.DEFAULT.maxBlobBytes());
    assertTrue(
        ProviderResourceMaterializer.MAX_REQUEST_INLINE_CHARS >= singleFileDataUriChars,
        "request 内联预算必须覆盖单文件上限：" + ProviderResourceMaterializer.MAX_REQUEST_INLINE_CHARS);
  }

  // ---------- 透传与顺序 ----------

  @Test
  void nonResourceBlocksPassThroughUnchanged() {
    ProviderTextBlock original = new ProviderTextBlock("plain text");
    List<ProviderMessage> materialized =
        materializer(mock(StorageBlobManager.class), mock(StorageBlobContentService.class))
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(original))),
                Set.of(),
                ProviderMediaCapabilities.NONE);

    assertSame(original, materialized.get(0).contents().get(0));
  }

  @Test
  void nestedTextResourceReconstructsToolResultWithMetadataAndOrder() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    ProviderResourceBlock resource = new ProviderResourceBlock(BLOB_ID, "scan.txt", "tiny preview");
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("text/plain", 42L));
    ProviderTextBlock before = new ProviderTextBlock("before");
    ProviderTextBlock after = new ProviderTextBlock("after");
    ProviderToolResultBlock original =
        new ProviderToolResultBlock(
            "call-text", "read", List.of(before, resource, after), true, "{\"source\":\"test\"}");

    ProviderMessage materializedMessage =
        materializer(blobManager, contentService)
            .materialize(
                List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(original))),
                Set.of(ModelInputModality.TEXT),
                ALL_MEDIA)
            .get(0);

    ProviderToolResultBlock materialized =
        assertInstanceOf(ProviderToolResultBlock.class, materializedMessage.contents().get(0));
    assertNotSame(original, materialized);
    assertEquals("call-text", materialized.toolCallId());
    assertEquals("read", materialized.toolName());
    assertTrue(materialized.error());
    assertEquals("{\"source\":\"test\"}", materialized.detailsJson());
    assertSame(before, materialized.contents().get(0));
    assertEquals(
        "[Resource: scan.txt]\n"
            + "blobId: 00000000-0000-0000-0000-000000000001\n"
            + "mediaType: text/plain\n"
            + "size: 42\n"
            + "preview: tiny preview",
        ((ProviderTextBlock) materialized.contents().get(1)).text());
    assertSame(after, materialized.contents().get(2));
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void toolResultWithoutResourcesRetainsIdentityAndNestedBlockOrder() {
    ProviderTextBlock first = new ProviderTextBlock("first");
    ProviderTextBlock second = new ProviderTextBlock("second");
    ProviderToolResultBlock original =
        new ProviderToolResultBlock(
            "call-plain", "read", List.of(first, second), false, "{\"ok\":true}");

    ProviderToolResultBlock materialized =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            materializer(mock(StorageBlobManager.class), mock(StorageBlobContentService.class))
                .materialize(
                    List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(original))),
                    Set.of(),
                    ProviderMediaCapabilities.NONE)
                .get(0)
                .contents()
                .get(0));

    assertSame(original, materialized);
    assertSame(first, materialized.contents().get(0));
    assertSame(second, materialized.contents().get(1));
  }

  // ---------- assistant provider replay state 透传 ----------

  /** 意图：物化边界不解析 replay payload；四种 Provider format 的 replay state 都必须以同一对象穿过本类。 */
  @ParameterizedTest
  @EnumSource(ProviderReplayFormat.class)
  void assistantReplayStateSurvivesMaterializationForEveryFormat(ProviderReplayFormat format) {
    ProviderReplayState replayState = replayState(format);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("answer"), new ProviderThinkingBlock("reasoning")),
            replayState);

    ProviderMessage materialized =
        materialize(List.of(assistant), Set.of(), ProviderMediaCapabilities.NONE).get(0);

    assertSame(replayState, materialized.replayState(), format + " replay state must be identical");
    assertSame(assistant.contents().get(0), materialized.contents().get(0));
    assertSame(assistant.contents().get(1), materialized.contents().get(1));
  }

  /** 意图：用户 Resource 与 assistant replay 混排时，顺序、Resource 物化与 replay identity 必须同时保持。 */
  @Test
  void mixedUserResourceAndAssistantReplayPreservesOrderAndReplayIdentity() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png", BYTES.length));
    stubContent(contentService, BLOB_ID, "image/png", BYTES);
    ProviderReplayState replayState = replayState(ProviderReplayFormat.OPENAI_CHAT);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("answer"), new ProviderThinkingBlock("reasoning")),
            replayState);

    List<ProviderMessage> materialized =
        materializer(blobManager, contentService)
            .materialize(
                List.of(
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE)),
                    assistant,
                    new ProviderMessage(ProviderMessageRole.USER, List.of(IMAGE_RESOURCE))),
                Set.of(ModelInputModality.IMAGE),
                ALL_MEDIA);

    assertEquals(3, materialized.size(), "消息顺序与数量必须保持不变");
    assertEquals(ProviderMessageRole.USER, materialized.get(0).role());
    assertEquals(ProviderMessageRole.ASSISTANT, materialized.get(1).role());
    assertEquals(ProviderMessageRole.USER, materialized.get(2).role());
    assertEquals(
        IMAGE_DATA_URI, ((ProviderImageBlock) materialized.get(0).contents().get(0)).source());
    assertEquals(
        IMAGE_DATA_URI, ((ProviderImageBlock) materialized.get(2).contents().get(0)).source());
    assertSame(replayState, materialized.get(1).replayState());
    assertTrue(materialized.get(1).hasReplayState());
    assertEquals(2, materialized.get(1).contents().size());
  }

  /** 意图：只有 assistant 允许携带 replay state；物化不得为其他角色合成 replay state。 */
  @Test
  void materializationNeverSynthesizesReplayStateForNonAssistantMessages() {
    ProviderReplayState replayState = replayState(ProviderReplayFormat.OPENAI_CHAT);
    List<ProviderMessage> materialized =
        materialize(
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("u"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("a")),
                    replayState)),
            Set.of(),
            ProviderMediaCapabilities.NONE);

    assertNull(materialized.get(0).replayState());
    assertSame(replayState, materialized.get(1).replayState());
  }

  /** 意图：无 replay state 的 assistant 消息物化后仍必须保持无 replay state，绝不伪造 thinking 或 replay。 */
  @Test
  void assistantWithoutReplayStateStaysWithoutReplayState() {
    List<ProviderMessage> materialized =
        materialize(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("plain")))),
            Set.of(),
            ProviderMediaCapabilities.NONE);

    assertFalse(materialized.get(0).hasReplayState());
    assertNull(materialized.get(0).replayState());
    assertEquals("plain", ((ProviderTextBlock) materialized.get(0).contents().get(0)).text());
  }

  // ---------- 构造 ----------

  @Test
  void rejectsNullDependenciesAndInvalidBudget() {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    assertThrows(
        NullPointerException.class, () -> new ProviderResourceMaterializer(null, contentService));
    assertThrows(
        NullPointerException.class, () -> new ProviderResourceMaterializer(blobManager, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProviderResourceMaterializer(
                    blobManager, contentService, defaultReaderLimits(), Ticker.systemTicker(), 1L)
                .materialize(null, Set.of(), ProviderMediaCapabilities.NONE));
    assertThrows(
        NullPointerException.class,
        () ->
            materializer(blobManager, contentService)
                .materialize(List.of(), null, ProviderMediaCapabilities.NONE));
    assertThrows(
        NullPointerException.class,
        () -> materializer(blobManager, contentService).materialize(List.of(), Set.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResourceMaterializer(
                blobManager, contentService, defaultReaderLimits(), Ticker.systemTicker(), 0L));
  }

  // ---------- 测试基座 ----------

  private static ProviderResourceMaterializer materializer(
      StorageBlobManager blobManager, StorageBlobContentService contentService) {
    return new ProviderResourceMaterializer(blobManager, contentService);
  }

  private static ProviderResourceMaterializer materializer(
      StorageBlobManager blobManager,
      StorageBlobContentService contentService,
      ProviderInlineBlobReader.Limits limits,
      long maxRequestInlineChars) {
    return new ProviderResourceMaterializer(
        blobManager, contentService, limits, Ticker.systemTicker(), maxRequestInlineChars);
  }

  private static List<ProviderMessage> materialize(
      List<ProviderMessage> messages,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities) {
    return materializer(mock(StorageBlobManager.class), mock(StorageBlobContentService.class))
        .materialize(messages, inputModalities, mediaCapabilities);
  }

  private static ProviderInlineBlobReader.Limits defaultReaderLimits() {
    return ProviderInlineBlobReader.Limits.DEFAULT;
  }

  /** 测试用上限：原始字节上限足够小，避免测试分配大对象。 */
  private static ProviderInlineBlobReader.Limits limits(long maxBlobBytes) {
    return new ProviderInlineBlobReader.Limits(
        maxBlobBytes, 1024L * 1024L, 4L * 1024L * 1024L, Duration.ofMinutes(5), 2);
  }

  private static void stubContent(
      StorageBlobContentService contentService, UUID blobId, String mediaType, byte[] bytes) {
    when(contentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(new StorageBlobContent(blobId, bytes, mediaType, bytes.length));
  }

  /** 合成 replay state：各 format 使用各自真实的 payload 形态，payload 对本边界保持完全不透明。 */
  private static ProviderReplayState replayState(ProviderReplayFormat format) {
    String payload =
        switch (format) {
          case OPENAI_RESPONSES -> "{\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}]}";
          case OPENAI_CHAT -> "{\"role\":\"assistant\",\"content\":\"opaque\",\"reasoning_content\":\"opaque\"}";
          case ANTHROPIC_MESSAGES -> "{\"content\":[{\"type\":\"thinking\",\"signature\":\"opaque\"}]}";
          case GEMINI_CONTENT -> "{\"parts\":[{\"thoughtSignature\":\"opaque\"}]}";
        };
    try {
      return new ProviderReplayState(
          format,
          new ProviderReplayAffinity(
              ProviderType.OPENAI, "test-provider", new UUID(0L, 9L), "test-model"),
          "0".repeat(64),
          new ObjectMapper().readTree(payload));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException(error);
    }
  }

  private static StorageBlob activeBlob(String mediaType, long sizeBytes) {
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_ID);
    blob.setMediaType(mediaType);
    blob.setSizeBytes(sizeBytes);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }
}
