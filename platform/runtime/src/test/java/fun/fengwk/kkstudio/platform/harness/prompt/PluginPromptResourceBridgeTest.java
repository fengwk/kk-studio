package fun.fengwk.kkstudio.platform.harness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/** Prompt 媒体窄端口的宿主实现回归：暂存的真实大小/MIME 校验、失败自清理，以及接收事务内的权威事实与 retain 顺序。 */
class PluginPromptResourceBridgeTest {

  private static final UUID UPLOAD_ID = UUID.randomUUID();
  private static final UUID BLOB_ID = UUID.randomUUID();
  private static final UUID SESSION_ID = UUID.randomUUID();

  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private PluginPromptResourceBridge bridge;

  @BeforeEach
  void setUp() {
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    bridge = new PluginPromptResourceBridge(uploadService, refManager);
  }

  private static InputStream bytes() {
    return new ByteArrayInputStream(new byte[] {1, 2, 3});
  }

  private static StorageUploadService.StagedUpload staged(String mediaType, long sizeBytes) {
    return new StorageUploadService.StagedUpload(
        UPLOAD_ID, BLOB_ID, "source.png", mediaType, sizeBytes, "a".repeat(64));
  }

  @Test
  void stageReturnsHandleWhenProbedFactsMatchTheExpectation() {
    when(uploadService.stage(anyString(), anyString(), any(InputStream.class), anyLong()))
        .thenReturn(staged("image/png", 3L));

    PluginPromptResources.StagedResource handle =
        bridge.stage("source.png", "image/png", bytes(), 3L);

    assertEquals(new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID), handle);
    verify(uploadService, never()).delete(any());
  }

  /** 探针媒体类型与声明不一致是确定性失败：必须先回收本次暂存再上抛。 */
  @Test
  void stageDiscardsAndFailsWhenProbedMediaTypeDiffers() {
    when(uploadService.stage(anyString(), anyString(), any(InputStream.class), anyLong()))
        .thenReturn(staged("image/jpeg", 3L));

    assertThrows(
        IllegalArgumentException.class, () -> bridge.stage("source.png", "image/png", bytes(), 3L));

    verify(uploadService).delete(UPLOAD_ID);
  }

  @Test
  void stageDiscardsAndFailsWhenProbedSizeDiffers() {
    when(uploadService.stage(anyString(), anyString(), any(InputStream.class), anyLong()))
        .thenReturn(staged("image/png", 4L));

    assertThrows(
        IllegalArgumentException.class, () -> bridge.stage("source.png", "image/png", bytes(), 3L));

    verify(uploadService).delete(UPLOAD_ID);
  }

  /** 清理失败不得遮蔽完整性失败本身（达期上传仍由 maintenance 兜底）。 */
  @Test
  void stageKeepsTheIntegrityFailureWhenCleanupFails() {
    when(uploadService.stage(anyString(), anyString(), any(InputStream.class), anyLong()))
        .thenReturn(staged("image/jpeg", 3L));
    doThrow(new StorageResourceNotFoundException("upload", UPLOAD_ID.toString()))
        .when(uploadService)
        .delete(UPLOAD_ID);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> bridge.stage("source.png", "image/png", bytes(), 3L));
    assertTrue(failure.getMessage().contains("integrity"), failure.getMessage());
  }

  @Test
  void stageValidatesItsInputs() {
    assertThrows(NullPointerException.class, () -> bridge.stage(null, "image/png", bytes(), 3L));
    assertThrows(NullPointerException.class, () -> bridge.stage("source.png", null, bytes(), 3L));
    assertThrows(
        NullPointerException.class, () -> bridge.stage("source.png", "image/png", null, 3L));
    assertThrows(
        IllegalArgumentException.class, () -> bridge.stage("source.png", "image/png", bytes(), 0L));
    verify(uploadService, never())
        .stage(anyString(), anyString(), any(InputStream.class), anyLong());
  }

  /** 接收只用上传行的权威文件名与 blobId，并让 Session 先 retain 再释放 upload owner。 */
  @Test
  void acceptBuildsResourceFromAuthoritativeFactsAndRetainsBeforeRelease() {
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "authoritative.png"));
    PluginPromptResources.StagedResource handle =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);

    ResourceMessageContent resource = bridge.accept(SESSION_ID, handle);

    assertEquals(BLOB_ID, resource.blobId());
    assertEquals("authoritative.png", resource.name());
    InOrder retainThenRelease = inOrder(refManager, uploadService);
    retainThenRelease.verify(refManager).retainRef(SESSION_ID, BLOB_ID);
    retainThenRelease.verify(uploadService).delete(UPLOAD_ID);
  }

  /** 暂存 blob 与 READY 上传行不一致属于身份错配：不得 retain，也不得释放 owner。 */
  @Test
  void acceptRejectsMismatchedBlobWithoutTouchingOwnership() {
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(UUID.randomUUID(), "authoritative.png"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            bridge.accept(
                SESSION_ID, new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID)));

    verify(refManager, never()).retainRef(any(), any());
    verify(uploadService, never()).delete(any());
  }

  /** 非 READY 是确定性拒绝：必须转成可控错误，不能泄漏存储实现异常。 */
  @Test
  void acceptRejectsNotReadyUploadDeterministically() {
    when(uploadService.lockReady(UPLOAD_ID))
        .thenThrow(new StorageVerificationException("still pending"));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                bridge.accept(
                    SESSION_ID, new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID)));
    assertFalse(failure.getMessage().contains("pending"), failure.getMessage());
    verify(refManager, never()).retainRef(any(), any());
  }

  /** 上传已不存在同样是确定性拒绝：不得泄漏存储异常，也不得触碰 Session 引用。 */
  @Test
  void acceptRejectsMissingUploadDeterministically() {
    when(uploadService.lockReady(UPLOAD_ID))
        .thenThrow(new StorageResourceNotFoundException("upload", UPLOAD_ID.toString()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            bridge.accept(
                SESSION_ID, new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID)));
    verify(refManager, never()).retainRef(any(), any());
    verify(uploadService, never()).delete(any());
  }

  /** discard 是准备阶段失败的显式清理入口：直接释放上传 owner。 */
  @Test
  void discardReleasesTheUploadOwner() {
    bridge.discard(new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID));

    verify(uploadService).delete(UPLOAD_ID);
  }
}
