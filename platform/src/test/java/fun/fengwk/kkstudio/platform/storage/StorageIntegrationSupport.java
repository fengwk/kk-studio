package fun.fengwk.kkstudio.platform.storage;

import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 存储集成测试共享基座：上传生命周期辅助方法（与 {@code StorageUploadServiceIntegrationTest} 同构）。 */
public class StorageIntegrationSupport {

  private final StorageUploadService storageUploadService;
  private final InMemoryS3StorageService s3Storage;
  private final JdbcTemplate jdbc;

  public StorageIntegrationSupport(
      StorageUploadService storageUploadService,
      InMemoryS3StorageService s3Storage,
      JdbcTemplate jdbc) {
    this.storageUploadService = storageUploadService;
    this.s3Storage = s3Storage;
    this.jdbc = jdbc;
  }

  public StorageUploadService storageUploadService() {
    return storageUploadService;
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

  /** 完成一次新上传并返回 blob id（blob ref_count = 1，upload 行持有该引用）。 */
  public String completeFirstUpload(byte[] content) {
    StorageUploadDTO pending =
        reserve("first.bin", "application/octet-stream", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "application/octet-stream");
    return storageUploadService.complete(UUID.fromString(pending.getId())).getBlobId();
  }

  public void backdateUpload(String uploadId) {
    jdbc.update(
        "update storage_upload set created_at = expires_at - interval '2 hours',"
            + " expires_at = current_timestamp - interval '1 minute' where id = ?",
        UUID.fromString(uploadId));
  }

  public long blobRefCount(String blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Long.class, UUID.fromString(blobId));
  }

  public int blobRowCount() {
    return jdbc.queryForObject("select count(*) from storage_blob", Integer.class);
  }

  public String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  public static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
