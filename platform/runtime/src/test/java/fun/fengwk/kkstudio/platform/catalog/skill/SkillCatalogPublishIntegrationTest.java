package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveIndex;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.error.DomainErrorCode;
import fun.fengwk.kkstudio.platform.error.DomainException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.skill.SkillPackageUploadPublishDTO;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Skill Package 发布与归档生命周期的 PostgreSQL 集成测试（使用内存 S3 假件与真实引用计数）。
 *
 * <p>覆盖：同 expectedVersion 的并发发布互斥（恰好一个成功，败者产生版本冲突）、版本发布成功原子切换归档并释放旧归档 blob 引用、 删除未引用的包清除权威行并释放归档
 * blob 引用。
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
class SkillCatalogPublishIntegrationTest extends PostgresSpringTestSupport {

  private static final String PACKAGE = "publish-pkg";

  @MockitoBean private SkillGitRepository skillGitRepository;
  @Autowired private SkillCatalogService skillCatalogService;
  @Autowired private SkillPackageRepository skillPackageRepository;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private StorageBlobManager storageBlobManager;
  @Autowired private StorageBlobRepository storageBlobRepository;

  /** 测试意图：相同 expectedVersion 的并发发布恰好一个成功，败者获得版本冲突。 */
  @Test
  void concurrentPublishWithSameExpectedVersionOneWinsAndOtherConflicts() throws Exception {
    // 初始发布包（version 0）
    UUID initialUpload = stageArchiveUpload(PACKAGE, "skill-init", "initial skill");
    SkillPackageUploadPublishDTO initDto = new SkillPackageUploadPublishDTO();
    initDto.setPackageName(PACKAGE);
    initDto.setPackageVersion("1.0.0");
    initDto.setDescription("initial description");
    initDto.setStagingUploadId(initialUpload.toString());
    skillCatalogService.publishUpload(initDto);

    SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
    assertNotNull(current);
    assertEquals(0L, current.version());

    // 准备两次并发发布候选，均基于 expectedVersion = "0"
    UUID stage1 = stageArchiveUpload(PACKAGE, "skill-v1", "v1 skill");
    UUID stage2 = stageArchiveUpload(PACKAGE, "skill-v2", "v2 skill");

    SkillPackageUploadPublishDTO publish1 = new SkillPackageUploadPublishDTO();
    publish1.setPackageName(PACKAGE);
    publish1.setPackageVersion("1.1.0");
    publish1.setDescription("v1.1 description");
    publish1.setExpectedVersion("0");
    publish1.setStagingUploadId(stage1.toString());

    SkillPackageUploadPublishDTO publish2 = new SkillPackageUploadPublishDTO();
    publish2.setPackageName(PACKAGE);
    publish2.setPackageVersion("1.2.0");
    publish2.setDescription("v1.2 description");
    publish2.setExpectedVersion("0");
    publish2.setStagingUploadId(stage2.toString());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch startLatch = new CountDownLatch(1);
    try {
      Future<SkillPackageDetailDTO> f1 =
          executor.submit(
              () -> {
                startLatch.await();
                return skillCatalogService.publishUpload(publish1);
              });
      Future<SkillPackageDetailDTO> f2 =
          executor.submit(
              () -> {
                startLatch.await();
                return skillCatalogService.publishUpload(publish2);
              });

      startLatch.countDown();

      int successCount = 0;
      int conflictCount = 0;

      for (Future<SkillPackageDetailDTO> future : List.of(f1, f2)) {
        try {
          SkillPackageDetailDTO result = future.get(10, TimeUnit.SECONDS);
          assertNotNull(result);
          successCount++;
        } catch (ExecutionException e) {
          if (e.getCause() instanceof DomainException domain
              && domain.code() == DomainErrorCode.VERSION_CONFLICT) {
            conflictCount++;
          } else {
            throw e;
          }
        }
      }

      assertEquals(1, successCount, "exactly one concurrent publish should succeed");
      assertEquals(1, conflictCount, "the loser must receive DomainException");

      SkillPackage after = skillPackageRepository.getPackage(PACKAGE);
      assertEquals(1L, after.version(), "package version must be incremented to 1");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 测试意图：成功发布切换归档并释放旧归档 blob 引用。 */
  @Test
  void successfulPublishSwitchesArtifactAndReleasesPreviousBlobReference() throws Exception {
    // 1. 初始发布
    UUID stage1 = stageArchiveUpload(PACKAGE, "skill-a", "skill a");
    SkillPackageUploadPublishDTO publish1 = new SkillPackageUploadPublishDTO();
    publish1.setPackageName(PACKAGE);
    publish1.setPackageVersion("1.0.0");
    publish1.setDescription("initial");
    publish1.setStagingUploadId(stage1.toString());
    skillCatalogService.publishUpload(publish1);

    SkillPackage pkg1 = skillPackageRepository.getPackage(PACKAGE);
    UUID blob1Id = pkg1.artifactBlobId();
    StorageBlob blob1AfterInit = storageBlobManager.getBlob(blob1Id);
    assertNotNull(blob1AfterInit);
    assertEquals(1L, blob1AfterInit.getRefCount());
    assertEquals(StorageBlobState.ACTIVE, blob1AfterInit.getState());

    // 2. 第二次发布，切换到新归档
    UUID stage2 = stageArchiveUpload(PACKAGE, "skill-b", "skill b");
    SkillPackageUploadPublishDTO publish2 = new SkillPackageUploadPublishDTO();
    publish2.setPackageName(PACKAGE);
    publish2.setPackageVersion("2.0.0");
    publish2.setDescription("updated");
    publish2.setExpectedVersion("0");
    publish2.setStagingUploadId(stage2.toString());
    skillCatalogService.publishUpload(publish2);

    SkillPackage pkg2 = skillPackageRepository.getPackage(PACKAGE);
    UUID blob2Id = pkg2.artifactBlobId();
    assertEquals(1L, pkg2.version());

    // 新归档 blob 持有 1 个引用且处于 ACTIVE
    StorageBlob blob2 = storageBlobManager.getBlob(blob2Id);
    assertNotNull(blob2);
    assertEquals(1L, blob2.getRefCount());
    assertEquals(StorageBlobState.ACTIVE, blob2.getState());

    // 旧归档 blob 引用被释放，计数为 0 并进入 DELETING
    StorageBlob blob1AfterSwitch = storageBlobManager.getBlob(blob1Id);
    assertNotNull(blob1AfterSwitch);
    assertEquals(0L, blob1AfterSwitch.getRefCount());
    assertEquals(StorageBlobState.DELETING, blob1AfterSwitch.getState());
  }

  /** 测试意图：删除未引用的包移除权威行并释放归档 blob 引用。 */
  @Test
  void deletePackageRemovesRowAndReleasesBlobReference() throws Exception {
    UUID stage = stageArchiveUpload(PACKAGE, "skill-a", "skill a");
    SkillPackageUploadPublishDTO publish = new SkillPackageUploadPublishDTO();
    publish.setPackageName(PACKAGE);
    publish.setPackageVersion("1.0.0");
    publish.setDescription("to delete");
    publish.setStagingUploadId(stage.toString());
    skillCatalogService.publishUpload(publish);

    SkillPackage pkg = skillPackageRepository.getPackage(PACKAGE);
    UUID blobId = pkg.artifactBlobId();
    StorageBlob blobBefore = storageBlobManager.getBlob(blobId);
    assertEquals(1L, blobBefore.getRefCount());
    assertEquals(StorageBlobState.ACTIVE, blobBefore.getState());

    // 删除包
    skillCatalogService.deletePackage(PACKAGE, "0");

    // 权威行被物理删除
    assertNull(skillPackageRepository.getPackage(PACKAGE));

    // blob 引用被释放为 0 并转入 DELETING
    StorageBlob blobAfter = storageBlobManager.getBlob(blobId);
    assertNotNull(blobAfter);
    assertEquals(0L, blobAfter.getRefCount());
    assertEquals(StorageBlobState.DELETING, blobAfter.getState());
  }

  private UUID stageArchiveUpload(String packageName, String skillName, String skillDesc)
      throws Exception {
    byte[] skillMd =
        ("---\nname: " + skillName + "\ndescription: " + skillDesc + "\n---\n")
            .getBytes(StandardCharsets.UTF_8);
    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    SkillArchiveBuilder.Result result =
        builder.fromEntries(
            List.of(new InputEntry(skillName + "/SKILL.md", Type.FILE, false, skillMd)));

    StorageUploadService.StagedUpload staged =
        storageUploadService.stage(
            packageName + ".zip",
            "application/zip",
            new ByteArrayInputStream(result.zip()),
            SkillArchiveBuilder.MAX_ARCHIVE_BYTES);
    storageBlobRepository.updateArchiveIndex(
        staged.blobId(), SkillArchiveIndex.from(result.index()).encode());
    return staged.uploadId();
  }
}
