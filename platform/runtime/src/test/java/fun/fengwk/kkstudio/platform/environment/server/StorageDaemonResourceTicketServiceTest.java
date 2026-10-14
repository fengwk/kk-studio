package fun.fengwk.kkstudio.platform.environment.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonResourceTicketService.Ticket;
import fun.fengwk.kkstudio.harness.environment.server.DaemonResourceTicketService.TransferRequest;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

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
    StorageDaemonResourceTicketService service =
        new StorageDaemonResourceTicketService(uploadService);

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
    StorageDaemonResourceTicketService service =
        new StorageDaemonResourceTicketService(uploadService);

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
    StorageDaemonResourceTicketService service =
        new StorageDaemonResourceTicketService(uploadService);

    Ticket.Failed failed =
        assertInstanceOf(
            Ticket.Failed.class, service.commit(ENVIRONMENT_ID, INVOCATION_ID, requested));

    assertEquals("resource upload is unavailable", failed.message());
  }

  /** 存储异常和含秘密的异常原文绝不能穿透到 Daemon 控制面。 */
  @Test
  void storageFailureIsSanitized() {
    StorageUploadService uploadService = mock(StorageUploadService.class);
    when(uploadService.reserve(any()))
        .thenThrow(new IllegalStateException("internal signed request must stay private"));
    StorageDaemonResourceTicketService service =
        new StorageDaemonResourceTicketService(uploadService);

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
        StorageUploadDTO.builder()
            .id(UUID.randomUUID().toString())
            .state(StorageUploadState.READY)
            .blobId(null)
            .presignedPut(presignedPut())
            .build();
    when(uploadService.reserve(any())).thenReturn(malformed);
    StorageDaemonResourceTicketService service =
        new StorageDaemonResourceTicketService(uploadService);

    Ticket.Failed failed =
        assertInstanceOf(
            Ticket.Failed.class,
            service.reserve(
                ENVIRONMENT_ID,
                INVOCATION_ID,
                new TransferRequest(UUID.randomUUID(), "image/png", "result.png", 123L, SHA256)));

    assertEquals("resource upload is unavailable", failed.message());
  }

  private static StorageUploadDTO pendingUpload(UUID uploadId) {
    return StorageUploadDTO.builder()
        .id(uploadId.toString())
        .state(StorageUploadState.PENDING)
        .blobId(null)
        .presignedPut(presignedPut())
        .build();
  }

  private static StorageUploadDTO readyUpload(UUID uploadId, UUID blobId) {
    return StorageUploadDTO.builder()
        .id(uploadId.toString())
        .state(StorageUploadState.READY)
        .blobId(blobId.toString())
        .presignedPut(null)
        .build();
  }

  private static StoragePresignedUrlDTO presignedPut() {
    return StoragePresignedUrlDTO.builder()
        .method("PUT")
        .url("https://storage.example.test/upload")
        .headers(Map.of("x-amz-checksum-sha256", "checksum"))
        .expiresAt("2026-07-28T10:01:00Z")
        .build();
  }
}
