package fun.fengwk.kkstudio.web.ai.chat;

import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** web 组合根 Chat 集成测试的共享上传辅助（真实 PostgreSQL + 内存 S3 假件）。 */
public class ChatIntegrationSupport {

  private final StorageUploadService storageUploadService;
  private final InMemoryS3StorageService s3Storage;
  private final JdbcTemplate jdbc;

  public ChatIntegrationSupport(
      StorageUploadService storageUploadService,
      InMemoryS3StorageService s3Storage,
      JdbcTemplate jdbc) {
    this.storageUploadService = storageUploadService;
    this.s3Storage = s3Storage;
    this.jdbc = jdbc;
  }

  public StorageUploadDTO reserve(String filename, String mediaType, long size, String sha256) {
    StorageUploadReserveRequestDTO request = new StorageUploadReserveRequestDTO();
    request.setFilename(filename);
    request.setMediaType(mediaType);
    request.setSizeBytes(size);
    request.setSha256(sha256);
    return storageUploadService.reserve(request);
  }

  public void putUploadContent(String uploadId, byte[] content, String contentType) {
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)), content, contentType);
  }

  /** 完成一次新上传并返回 upload 行 id（READY，blob ref_count = 1 由 upload 持有）。 */
  public String completeUpload(String filename, byte[] content) {
    StorageUploadDTO pending = reserve(filename, "text/plain", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "text/plain");
    storageUploadService.complete(UUID.fromString(pending.getId()));
    return pending.getId();
  }

  public long blobRefCount(String blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Long.class, UUID.fromString(blobId));
  }

  public String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }
}
