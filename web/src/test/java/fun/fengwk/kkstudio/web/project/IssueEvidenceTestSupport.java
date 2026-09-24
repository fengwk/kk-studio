package fun.fengwk.kkstudio.web.project;

import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issue 公开证据 web 集成测试的共享辅助：可控上传、blob 账本与行计数。
 *
 * <p>上传路径与 {@code ChatIntegrationSupport} 同构（真实 reserve/complete 校验 + 内存 S3 直传对象），使证据测试不复制生产逻辑。
 */
public class IssueEvidenceTestSupport {

  private static final String MEDIA_TYPE = "text/plain";

  private final StorageUploadService uploadService;
  private final InMemoryS3StorageService s3Storage;
  private final JdbcTemplate jdbc;

  public IssueEvidenceTestSupport(
      StorageUploadService uploadService, InMemoryS3StorageService s3Storage, JdbcTemplate jdbc) {
    this.uploadService = uploadService;
    this.s3Storage = s3Storage;
    this.jdbc = jdbc;
  }

  /** 完成一次新上传并返回 READY upload 行 id（blob 唯一引用由 upload 持有）。 */
  public UUID readyUpload(String filename, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    StorageUploadReserveRequestDTO reserve = new StorageUploadReserveRequestDTO();
    reserve.setFilename(filename);
    reserve.setMediaType(MEDIA_TYPE);
    reserve.setSizeBytes(bytes.length);
    reserve.setSha256(sha256Hex(bytes));
    StorageUploadDTO pending = uploadService.reserve(reserve);
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(pending.getId())), bytes, MEDIA_TYPE);
    uploadService.complete(UUID.fromString(pending.getId()));
    return UUID.fromString(pending.getId());
  }

  public UUID blobIdOf(UUID uploadId) {
    return jdbc.queryForObject(
        "select blob_id from storage_upload where id = ?", UUID.class, uploadId);
  }

  public long refCount(UUID blobId) {
    Long refCount =
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId);
    return refCount != null ? refCount : -1L;
  }

  public String blobState(UUID blobId) {
    return jdbc.queryForObject("select state from storage_blob where id = ?", String.class, blobId);
  }

  public int count(String table, String column, Object value) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    return count != null ? count : 0;
  }

  public String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }
}
