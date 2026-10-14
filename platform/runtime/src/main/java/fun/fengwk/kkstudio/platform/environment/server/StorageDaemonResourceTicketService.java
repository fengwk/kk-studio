package fun.fengwk.kkstudio.platform.environment.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.harness.environment.server.DaemonResourceTicketService;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link DaemonResourceTicketService} 的 Platform 实现：把 Daemon 的调用作用域上传映射到全局 Blob 上传契约。
 *
 * <p>语义完全由 {@link StorageUploadService} 决定：{@code reserve} 命中 ACTIVE 内容时直接 READY（Daemon 无需上传字节），
 * 未命中时 PENDING 加携带 {@code x-amz-checksum-sha256} 与 {@code If-None-Match: *} 的预签名 PUT；{@code commit}
 * 校验直传对象的 真实大小与校验和后绑定 blob；{@code release} 只请求全局清理。
 *
 * <p><b>不泄漏物理事实：</b>bucket 与对象物理 key 从不进入票据；失败只回执有界、无敏感信息的说明（上传行 id 与状态足够让 Daemon 决策）。
 */
@Slf4j
@Service
public class StorageDaemonResourceTicketService implements DaemonResourceTicketService {

  /** 缺省文件名前缀：Daemon 未声明展示名时给出稳定、非空且不泄漏物理 key 的展示名。 */
  private static final String FALLBACK_NAME_PREFIX = "resource-";

  private final StorageUploadService uploadService;

  public StorageDaemonResourceTicketService(StorageUploadService uploadService) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
  }

  @Override
  public Ticket reserve(EnvironmentId environmentId, String invocationId, TransferRequest request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(request, "request");
    StorageUploadReserveRequestDTO reserve = new StorageUploadReserveRequestDTO();
    reserve.setFilename(filenameOf(request));
    reserve.setMediaType(request.mediaType());
    reserve.setSizeBytes(request.size());
    reserve.setSha256(request.sha256());
    try {
      return toTicket(uploadService.reserve(reserve));
    } catch (RuntimeException error) {
      return failed();
    }
  }

  @Override
  public Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(uploadId, "uploadId");
    try {
      Ticket ticket = toTicket(uploadService.complete(uploadId));
      if (!(ticket instanceof Ticket.Ready ready) || !uploadId.equals(ready.uploadId())) {
        throw new IllegalStateException("complete must return READY for the requested upload");
      }
      return ticket;
    } catch (StorageVerificationException error) {
      // 对象尚未就绪或校验失败是可重试事实，不回显服务端细节。
      return new Ticket.Failed("uploaded object is not ready yet");
    } catch (RuntimeException error) {
      return failed();
    }
  }

  @Override
  public void release(EnvironmentId environmentId, String invocationId, UUID uploadId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(invocationId, "invocationId");
    if (uploadId == null) {
      return;
    }
    try {
      uploadService.delete(uploadId);
    } catch (StorageResourceNotFoundException ignored) {
      // 已被消费或已被后台回收：释放本就幂等。
    } catch (RuntimeException error) {
      log.warn("cannot release daemon resource upload {}", uploadId);
    }
  }

  /** 稳定且非空的展示名：Daemon 声明优先，否则按传输 id 派生。 */
  private static String filenameOf(TransferRequest request) {
    if (request.name() != null && !request.name().isBlank()) {
      return request.name();
    }
    return FALLBACK_NAME_PREFIX + request.transferId();
  }

  private static Ticket toTicket(StorageUploadDTO dto) {
    Objects.requireNonNull(dto, "storage upload");
    UUID uploadId = parseUploadId(dto.getId());
    if (dto.getState() == StorageUploadState.READY) {
      if (dto.getBlobId() == null || dto.getPresignedPut() != null) {
        throw new IllegalStateException("READY upload has an invalid shape");
      }
      parseCanonicalUuid(dto.getBlobId(), "blobId");
      return new Ticket.Ready(uploadId);
    }
    if (dto.getState() != StorageUploadState.PENDING || dto.getBlobId() != null) {
      throw new IllegalStateException("storage upload has an invalid state");
    }
    StoragePresignedUrlDTO presigned = dto.getPresignedPut();
    if (presigned == null || presigned.getMethod() == null || presigned.getUrl() == null) {
      throw new IllegalStateException("PENDING upload must carry a presigned PUT");
    }
    Map<String, String> headers =
        presigned.getHeaders() == null ? Map.of() : Map.copyOf(presigned.getHeaders());
    return new Ticket.Pending(
        uploadId,
        new DaemonPresignedPut(
            presigned.getMethod(), presigned.getUrl(), headers, parseExpiresAt(presigned)));
  }

  /** 预签名签名过期时刻：wire 上的权威 ISO-8601 事实；缺失或非法都是服务端缺陷，直接失败。 */
  private static Instant parseExpiresAt(StoragePresignedUrlDTO presigned) {
    String expiresAt = presigned.getExpiresAt();
    if (expiresAt == null || expiresAt.isBlank()) {
      throw new IllegalStateException("PENDING upload must carry presigned expiresAt");
    }
    try {
      Instant parsed = Instant.parse(expiresAt);
      if (!parsed.toString().equals(expiresAt)) {
        throw new IllegalStateException("presigned expiresAt must be canonical");
      }
      return parsed;
    } catch (DateTimeParseException error) {
      throw new IllegalStateException("presigned expiresAt must be an ISO-8601 instant", error);
    }
  }

  private static UUID parseUploadId(String id) {
    return parseCanonicalUuid(id, "uploadId");
  }

  private static UUID parseCanonicalUuid(String value, String field) {
    Objects.requireNonNull(value, field);
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalStateException(field + " must be a canonical UUID");
    }
    return parsed;
  }

  private static Ticket failed() {
    // 只报告有界、无敏感信息的失败说明：单次传输失败不应把存储细节暴露给 Daemon。
    return new Ticket.Failed("resource upload is unavailable");
  }
}
