package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobPreviewService;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 动态 upload TTL 的真实 PostgreSQL 集成测试（内存 S3 假件）。
 *
 * <p>覆盖：缩短 TTL 立即让存量过期并清理；延长 TTL 立即让未 claim 存量继续可消费；已 claim 的清理事实即使 lease 过期、TTL 被延长也只会 续完清理而不复活；临时
 * upload TTL 不误删仍被持久引用的 blob。
 */
@Import(StorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class StorageUploadTtlPolicyIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private StorageUploadService uploadService;
  @Autowired private StorageBlobManager blobManager;
  @Autowired private StorageUploadRepository uploadRepository;
  @Autowired private SystemSettingsSnapshot settingsSnapshot;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  // 本类显式驱动 expireOnce；禁用会异步 claim 同一批 upload 的后台 maintenance，并避免真实媒体预览。
  @MockitoBean private StorageMaintenance storageMaintenance;
  @MockitoBean private StorageBlobPreviewService blobPreviewService;

  private StorageIntegrationSupport storage;
  private TransactionTemplate tx;

  @BeforeEach
  void setUpStorage() {
    s3Storage.clear();
    settingsSnapshot.replace(SystemSettings.DEFAULT);
    storage = new StorageIntegrationSupport(uploadService, s3Storage, jdbc);
    tx = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void restoreDefaultSettings() {
    // snapshot 是类级共享 bean：每个测试后恢复默认，避免 TTL 热更新泄漏到其它用例。
    settingsSnapshot.replace(SystemSettings.DEFAULT);
  }

  /** 缩短 TTL：存量未 claim 上传在下一次判断立即过期，complete 拒绝且后台清理删除对象与行。 */
  @Test
  void shorteningTtlExpiresExistingUnclaimedUploadImmediately() {
    byte[] content = "shorten".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "shorten.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    UUID uploadId = UUID.fromString(pending.getId());

    setUploadTtl(1L);
    backdateCreatedAt(uploadId, 5L);

    StorageVerificationException error =
        assertThrows(StorageVerificationException.class, () -> uploadService.complete(uploadId));
    assertTrue(error.getMessage().contains("expired"), "actual: " + error.getMessage());

    assertEquals(1, uploadService.expireOnce(), "the expired unclaimed upload must be reclaimed");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadId));
    assertTrue(
        s3Storage.networkCalls().stream().anyMatch(call -> "deleteObject".equals(call.operation())),
        "cleanup must delete the temp object");
  }

  /** 延长 TTL：先被缩短判定为过期的存量 READY 上传，在下一次消费判断重新变为可消费。 */
  @Test
  void extendingTtlKeepsUnclaimedReadyUploadConsumable() {
    byte[] content = "extend".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "extend.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    UUID uploadId = UUID.fromString(pending.getId());
    StorageUploadDTO ready = uploadService.complete(uploadId);

    // 缩短 TTL 后回拨创建时间：该 READY 存量在上一次判断中已过期，消费被拒绝。
    setUploadTtl(1L);
    backdateCreatedAt(uploadId, 5L);
    StorageVerificationException error =
        assertThrows(
            StorageVerificationException.class,
            () -> tx.execute(status -> uploadService.lockReady(uploadId)));
    assertTrue(error.getMessage().contains("expired"), "actual: " + error.getMessage());

    // 延长 TTL：同一条未 claim 上传立即恢复可消费（每次消费现读最新 policy），且不再是清理候选。
    setUploadTtl(86_400L);
    assertEquals(0, uploadService.expireOnce(), "an extended unclaimed upload must not be cleaned");
    StorageUploadService.ReadyUpload consumed =
        tx.execute(status -> uploadService.lockReady(uploadId));
    assertEquals(UUID.fromString(ready.getBlobId()), consumed.blobId());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadId),
        "an extended unclaimed upload must survive");
  }

  /** 已 claim 的清理事实：即使 lease 过期、TTL 被大幅延长，也只续完清理，绝不复活为可存活上传。 */
  @Test
  void claimedCleanupIsFinalizedEvenAfterTtlExtended() {
    byte[] content = "claimed".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "claimed.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    UUID uploadId = UUID.fromString(pending.getId());

    // 模拟 claim 已开始但节点中断：cleanup_token 已写入、lease 已过期。
    setUploadTtl(1L);
    backdateCreatedAt(uploadId, 5L);
    Instant now = Instant.now();
    tx.execute(
        status ->
            uploadRepository.claimById(uploadId, now, now.plusMillis(1), "interrupted-owner"));
    jdbc.update(
        "update storage_upload set cleanup_until = current_timestamp - interval '1 second' where id = ?",
        uploadId);

    // TTL 被大幅延长：已 claim 的行不受影响，仍必须被续完清理。
    setUploadTtl(86_400L);
    assertThrows(
        StorageVerificationException.class,
        () -> tx.execute(status -> uploadService.lockReady(uploadId)),
        "a claimed upload must never be consumable again");
    assertEquals(1, uploadService.expireOnce(), "a claimed cleanup must always be finalized");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadId));
  }

  /** 临时 upload TTL 只释放 upload 自己的引用：仍被持久 owner 引用的 blob 与对象不随之删除。 */
  @Test
  void persistentBlobReferenceSurvivesUploadTtlExpiry() {
    byte[] content = "persistent".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "persistent.bin",
            "application/octet-stream",
            content.length,
            storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    UUID uploadId = UUID.fromString(pending.getId());
    StorageUploadDTO ready = uploadService.complete(uploadId);
    UUID blobId = UUID.fromString(ready.getBlobId());

    // 持久 owner（Session 引用/Canvas 资源）：在 upload 引用之外再加一个引用。
    tx.execute(status -> blobManager.retain(blobId));
    assertEquals(2L, storage.blobRefCount(ready.getBlobId()));

    setUploadTtl(1L);
    backdateCreatedAt(uploadId, 5L);
    assertEquals(1, uploadService.expireOnce(), "the expired upload must be cleaned");

    assertEquals(
        1L,
        storage.blobRefCount(ready.getBlobId()),
        "only the upload's reference is released; the persistent owner keeps the blob");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ? and state = ?",
            Integer.class,
            blobId,
            StorageBlobState.ACTIVE.name()),
        "a persistently referenced blob must stay ACTIVE");
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)),
        "a persistently referenced blob object must survive the upload TTL expiry");
  }

  private void setUploadTtl(long ttlSeconds) {
    SystemSettings.StorageMedia base = settingsSnapshot.get().storageMedia();
    settingsSnapshot.replace(
        new SystemSettings(
            SystemSettings.DEFAULT.tool(),
            SystemSettings.DEFAULT.aiRuntime(),
            SystemSettings.DEFAULT.environment(),
            SystemSettings.DEFAULT.network(),
            SystemSettings.DEFAULT.integrations(),
            new SystemSettings.StorageMedia(
                ttlSeconds,
                base.s3PresignDefaultExpiresSeconds(),
                base.s3PresignMaxExpiresSeconds(),
                base.canvasMediaProcessTimeoutMillis(),
                base.thumbnailMaxDimension(),
                base.thumbnailQuality()),
            SystemSettings.DEFAULT.advanced()));
  }

  private void backdateCreatedAt(UUID uploadId, long secondsAgo) {
    jdbc.update(
        "update storage_upload set created_at ="
            + " current_timestamp - make_interval(secs => CAST(? AS double precision))"
            + " where id = ?",
        secondsAgo,
        uploadId);
  }
}
