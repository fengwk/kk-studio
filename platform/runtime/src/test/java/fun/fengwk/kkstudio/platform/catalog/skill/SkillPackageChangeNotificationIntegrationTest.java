package fun.fengwk.kkstudio.platform.catalog.skill;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.error.DomainException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageGitCreateDTO;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill Package 生产写入口的事务内 {@code skill_package_changed} 通知验证。
 *
 * <p>意图：真实 Java 写入口（仓储 insert/update/delete 与 {@code SkillCatalogService} 的 {@code NOT_SUPPORTED}
 * 外层 + {@code SkillCatalogWrites.REQUIRES_NEW} 内层事务）在成功写事实的同一事务内发布失效提示，由独立非池化 PostgreSQL LISTEN
 * 连接观测。覆盖提交后投递、 未提交不可见、回滚静默、CAS 0 行与未变化编辑静默、无事务调用在写行前拒绝。
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
class SkillPackageChangeNotificationIntegrationTest extends PostgresSpringTestSupport {

  private static final String PACKAGE = "notify-package";
  private static final String REPOSITORY_URL = "https://git.example.com/notify-package.git";
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @MockitoBean private SkillGitRepository skillGitRepository;
  @Autowired private SkillCatalogService skillCatalogService;
  @Autowired private SkillPackageRepository skillPackageRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  /** 插入/版本变化/删除都在提交后投递一次；未提交不可见，回滚静默且不改变权威事实。 */
  @Test
  void committedWriteNotifiesAndUncommittedOrRolledBackIsSilent() throws Exception {
    UUID blobId = insertRawBlob();
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      // 未提交的插入对其它连接不可见。
      tx.executeWithoutResult(
          status -> {
            assertTrue(skillPackageRepository.insertPackage(packageRow(blobId)));
            assertNoNotification(pg);
          });
      assertNotification(pg, PACKAGE);
      assertNoNotification(pg);

      // 回滚的更新绝不投递，也不改变权威 description。
      tx.executeWithoutResult(
          status -> {
            SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
            assertTrue(
                skillPackageRepository.updateDescription(
                    PACKAGE, "rolled-back-desc", current.version()));
            status.setRollbackOnly();
          });
      assertNoNotification(pg);
      SkillPackage rolledBack = skillPackageRepository.getPackage(PACKAGE);
      assertEquals(0L, rolledBack.version());
      assertEquals("notify package", rolledBack.description());

      // 提交的更新投递一次。
      tx.executeWithoutResult(
          status -> {
            SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
            assertTrue(
                skillPackageRepository.updateDescription(
                    PACKAGE, "updated-desc", current.version()));
          });
      assertNotification(pg, PACKAGE);
      assertEquals(1L, skillPackageRepository.getPackage(PACKAGE).version());

      // 删除投递一次。
      tx.executeWithoutResult(
          status -> assertTrue(skillPackageRepository.deletePackage(PACKAGE, 1L)));
      assertNotification(pg, PACKAGE);
      assertNull(skillPackageRepository.getPackage(PACKAGE));
    }
  }

  /** 陈旧 CAS 影响 0 行静默；生产编辑用例在事实未变化时不写行也不通知，陈旧版本冲突同样静默。 */
  @Test
  void staleCasAndUnchangedEditAreSilent() throws Exception {
    UUID blobId = insertRawBlob();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> assertTrue(skillPackageRepository.insertPackage(packageRow(blobId))));

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);

      // 陈旧 CAS：0 行受影响，version 未推进，静默。
      tx.executeWithoutResult(
          status -> {
            SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
            assertFalse(
                skillPackageRepository.updateDescription(
                    PACKAGE, "new-desc", current.version() + 5));
            assertFalse(skillPackageRepository.updatePublication(current, current.version() + 5));
            assertFalse(skillPackageRepository.deletePackage(PACKAGE, current.version() + 5));
          });
      assertNoNotification(pg);

      // 编辑可编辑字段但事实未变化：服务直接返回，不写行、不通知。
      SkillPackageEditDTO unchanged = new SkillPackageEditDTO();
      unchanged.setExpectedVersion("0");
      unchanged.setDescription("notify package");
      unchanged.setBranch("main");
      skillCatalogService.editPackage(PACKAGE, unchanged);
      assertNoNotification(pg);
      assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).version());

      // 陈旧版本编辑：在写行前就 conflict，静默。
      SkillPackageEditDTO stale = new SkillPackageEditDTO();
      stale.setExpectedVersion("9");
      stale.setDescription("changed");
      stale.setBranch("release");
      assertThrows(DomainException.class, () -> skillCatalogService.editPackage(PACKAGE, stale));
      assertNoNotification(pg);
      assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).version());
    }
  }

  /** 外层 NOT_SUPPORTED + 内层 REQUIRES_NEW：Create 的通知必须落在内层实际写事务内并在提交后投递。 */
  @Test
  void createPackagePublishesInsideInnerRequiresNewTransaction() throws Exception {
    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    SkillArchiveBuilder.Result archive =
        builder.fromEntries(
            List.of(
                new InputEntry(
                    "dev/SKILL.md",
                    Type.FILE,
                    false,
                    "---\nname: dev\ndescription: dev skill\n---\n"
                        .getBytes(StandardCharsets.UTF_8))));

    when(skillGitRepository.resolveBranchHead(REPOSITORY_URL, "main", null)).thenReturn(COMMIT);
    when(skillGitRepository.buildArchive(REPOSITORY_URL, COMMIT, null)).thenReturn(archive);

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);

      SkillPackageGitCreateDTO create = new SkillPackageGitCreateDTO();
      create.setPackageName(PACKAGE);
      create.setDescription("notify package");
      create.setRepositoryUrl(REPOSITORY_URL);
      create.setBranch("main");

      // 外层方法 NOT_SUPPORTED：若通知被放到无事务路径，requireTransaction 会直接拒绝。
      skillCatalogService.createGitPackage(create);

      assertNotification(pg, PACKAGE);
      assertNoNotification(pg);
      assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).version());
    }
  }

  /** 无事务直接调用仓储写路径：在执行 SQL 前拒绝，权威行原样不变，listener 静默。 */
  @Test
  void repositoryWriteWithoutTransactionIsRejectedBeforeAnyWrite() throws Exception {
    UUID blobId = insertRawBlob();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> assertTrue(skillPackageRepository.insertPackage(packageRow(blobId))));
    SkillPackage before = skillPackageRepository.getPackage(PACKAGE);

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);

      // 写入口都在执行 SQL 前拒绝，未产生 autocommit 写入。
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.insertPackage(packageRow(blobId)));
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.updateDescription(PACKAGE, "new-desc", before.version()));
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.updatePublication(before, before.version()));
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.updateGitObservation(PACKAGE, COMMIT, Instant.now(), null));
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.updateGitSettings(PACKAGE, "release", null));
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.deletePackage(PACKAGE, before.version()));

      SkillPackage after = skillPackageRepository.getPackage(PACKAGE);
      assertNotNull(after);
      assertEquals(before.version(), after.version());
      assertEquals(before.description(), after.description());
      assertNoNotification(pg);
    }
  }

  private UUID insertRawBlob() {
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state) values (?, ?, 1, 'application/zip', 1, 'ACTIVE')",
        blobId,
        "a".repeat(64));
    return blobId;
  }

  private static SkillPackage packageRow(UUID blobId) {
    return new SkillPackage(
        PACKAGE,
        "notify package",
        COMMIT,
        blobId,
        List.of(new SkillManifestEntry("dev", "developer skill")),
        Instant.now(),
        0L,
        Instant.now(),
        Instant.now(),
        new SkillPackageGitSource(REPOSITORY_URL, "main", null, COMMIT, Instant.now(), null));
  }

  private static void assertNotification(PGConnection pg, String expectedPayload) {
    List<PGNotification> notifications = drain(pg, 2000);
    assertEquals(1, notifications.size());
    assertEquals(EnvironmentSkillSyncOrchestrator.CHANNEL, notifications.get(0).getName());
    assertEquals(expectedPayload, notifications.get(0).getParameter());
  }

  private static void assertNoNotification(PGConnection pg) {
    assertTrue(drain(pg, 200).isEmpty());
  }

  private static List<PGNotification> drain(PGConnection pg, int timeoutMillis) {
    try {
      PGNotification[] notifications = pg.getNotifications(timeoutMillis);
      return notifications == null ? List.of() : List.of(notifications);
    } catch (SQLException error) {
      throw new AssertionError("cannot read PostgreSQL notifications", error);
    }
  }
}
