package fun.fengwk.kkstudio.platform.environment.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService.DownloadTicket;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService.Ticket;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService.TransferRequest;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadPendingDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReadyDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUrlMethod;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignedUrl;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Daemon 资源票据到全局 StorageUploadService 的适配契约测试。 */
class StorageDaemonResourceTicketServiceTest {

  private static final EnvironmentId ENVIRONMENT_ID = EnvironmentId.of(UUID.randomUUID());
  private static final String INVOCATION_ID = UUID.randomUUID().toString();
  private static final String SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  /** PENDING reservation 必须原样映射签名事实，并为无名资源生成稳定展示名。 */
  @Test
  void reserveMapsPendingUploadAndStableFallbackName() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    UUID uploadId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    StorageUploadDTO pending = pendingUpload(uploadId);
    when(uploadService.reserve(any())).thenReturn(pending);
    StorageDaemonResourceTicketService service = newService(uploadService);

    Ticket.Pending ticket =
        assertInstanceOf(
            Ticket.Pending.class,
            service.reserve(
                ENVIRONMENT_ID,
                INVOCATION_ID,
                new TransferRequest(transferId, "image/png", null, 123L, SHA256)));

    assertEquals(uploadId, ticket.uploadId());
    assertEquals("PUT", ticket.presignedPut().method());
    assertEquals("https://storage.example.test/upload", ticket.presignedPut().url());
    assertEquals(Map.of("x-amz-checksum-sha256", "checksum"), ticket.presignedPut().headers());
    assertEquals(Instant.parse("2026-07-28T10:01:00Z"), ticket.presignedPut().expiresAt());

    ArgumentCaptor<StorageUploadReserveRequestDTO> requestCaptor =
        ArgumentCaptor.forClass(StorageUploadReserveRequestDTO.class);
    verify(uploadService).reserve(requestCaptor.capture());
    StorageUploadReserveRequestDTO request = requestCaptor.getValue();
    assertEquals("resource-" + transferId, request.getFilename());
    assertEquals("image/png", request.getMediaType());
    assertEquals(123L, request.getSizeBytes());
    assertEquals(SHA256, request.getSha256());
  }

  /** 已去重命中的 READY reservation 只返回上传 owner id，不向 Daemon 暴露 blob id。 */
  @Test
  void reserveMapsReadyUpload() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    UUID uploadId = UUID.randomUUID();
    when(uploadService.reserve(any())).thenReturn(readyUpload(uploadId, UUID.randomUUID()));
    StorageDaemonResourceTicketService service = newService(uploadService);

    Ticket.Ready ticket =
        assertInstanceOf(
            Ticket.Ready.class,
            service.reserve(
                ENVIRONMENT_ID,
                INVOCATION_ID,
                new TransferRequest(UUID.randomUUID(), "image/png", "result.png", 123L, SHA256)));

    assertEquals(uploadId, ticket.uploadId());
  }

  /** commit 只能接受服务返回的同一 READY upload；错配或畸形状态必须收敛为无细节失败。 */
  @Test
  void commitRequiresMatchingReadyUpload() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    UUID requested = UUID.randomUUID();
    when(uploadService.complete(requested))
        .thenReturn(readyUpload(UUID.randomUUID(), UUID.randomUUID()));
    StorageDaemonResourceTicketService service = newService(uploadService);

    Ticket.Failed failed =
        assertInstanceOf(
            Ticket.Failed.class, service.commit(ENVIRONMENT_ID, INVOCATION_ID, requested));

    assertEquals("resource upload is unavailable", failed.message());
  }

  /** Daemon 后台协议将存储验证冲突转换成不泄漏内部细节的失败票据。 */
  @Test
  void commitMapsStorageVerificationToSafeFailure() {
    StorageUploadService uploads = mock(StorageUploadService.class);
    UUID uploadId = UUID.randomUUID();
    when(uploads.complete(uploadId))
        .thenThrow(DomainException.conflict("STORAGE_VERIFICATION", "private detail"));
    Ticket.Failed failure =
        assertInstanceOf(
            Ticket.Failed.class,
            newService(uploads).commit(ENVIRONMENT_ID, INVOCATION_ID, uploadId));
    assertEquals("uploaded object is not ready yet", failure.message());
  }

  /** 存储异常和含秘密的异常原文绝不能穿透到 Daemon 控制面。 */
  @Test
  void storageFailureIsSanitized() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    when(uploadService.reserve(any()))
        .thenThrow(new IllegalStateException("internal signed request must stay private"));
    StorageDaemonResourceTicketService service = newService(uploadService);

    Ticket.Failed failed =
        assertInstanceOf(
            Ticket.Failed.class,
            service.reserve(
                ENVIRONMENT_ID,
                INVOCATION_ID,
                new TransferRequest(UUID.randomUUID(), "image/png", "result.png", 123L, SHA256)));

    assertEquals("resource upload is unavailable", failed.message());
  }

  /** READY/PENDING 的互斥字段必须在适配边界校验，不能把矛盾状态交给会话核心。 */
  @Test
  void rejectsMalformedStorageUploadShape() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageUploadDTO malformed =
        new StorageUploadReadyDTO(
            UUID.randomUUID().toString(), null, Instant.parse("2026-07-28T10:01:00Z"));
    when(uploadService.reserve(any())).thenReturn(malformed);
    StorageDaemonResourceTicketService service = newService(uploadService);

    Ticket.Failed failed =
        assertInstanceOf(
            Ticket.Failed.class,
            service.reserve(
                ENVIRONMENT_ID,
                INVOCATION_ID,
                new TransferRequest(UUID.randomUUID(), "image/png", "result.png", 123L, SHA256)));

    assertEquals("resource upload is unavailable", failed.message());
  }

  /** 下载票据必须引用已归档 blob，并复制持久化大小与摘要，而非重新推导。 */
  @Test
  void downloadUsesArchiveBlobMetadata() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    S3PresignService presignService = mock(S3PresignService.class);
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex("{}");
    blob.setSizeBytes(321L);
    blob.setSha256(SHA256);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    Instant expiresAt = Instant.parse("2026-07-28T10:01:00Z");
    when(presignService.presignDownload(any(), isNull()))
        .thenReturn(
            S3PresignedUrl.builder()
                .method("GET")
                .url("https://storage.example.test/archive")
                .expiresAt(expiresAt)
                .build());
    StorageDaemonResourceTicketService service =
        new StorageDaemonResourceTicketService(uploadService, blobManager, presignService);

    DownloadTicket.Ready ticket =
        assertInstanceOf(
            DownloadTicket.Ready.class,
            service.download(ENVIRONMENT_ID, INVOCATION_ID, UUID.randomUUID(), blobId));

    assertEquals("GET", ticket.presignedGet().method());
    assertEquals("https://storage.example.test/archive", ticket.presignedGet().url());
    assertEquals(expiresAt.toEpochMilli(), ticket.presignedGet().expiresAtEpochMs());
    assertEquals(321L, ticket.size());
    assertEquals(SHA256, ticket.sha256());
    verify(presignService).presignDownload("blobs/" + blobId + "/original", null);
  }

  private static StorageDaemonResourceTicketService newService(StorageUploadService uploadService) {
    return new StorageDaemonResourceTicketService(
        uploadService, mock(StorageBlobManager.class), mock(S3PresignService.class));
  }

  private static StorageUploadDTO pendingUpload(UUID uploadId) {
    return new StorageUploadPendingDTO(
        uploadId.toString(), presignedPut(), Instant.parse("2026-07-28T10:01:00Z"));
  }

  private static StorageUploadDTO readyUpload(UUID uploadId, UUID blobId) {
    return new StorageUploadReadyDTO(
        uploadId.toString(), blobId.toString(), Instant.parse("2026-07-28T10:01:00Z"));
  }

  private static StoragePresignedUrlDTO presignedPut() {
    return StoragePresignedUrlDTO.builder()
        .method(StorageUrlMethod.PUT)
        .url("https://storage.example.test/upload")
        .headers(Map.of("x-amz-checksum-sha256", "checksum"))
        .expiresAt(Instant.parse("2026-07-28T10:01:00Z"))
        .build();
  }
}
