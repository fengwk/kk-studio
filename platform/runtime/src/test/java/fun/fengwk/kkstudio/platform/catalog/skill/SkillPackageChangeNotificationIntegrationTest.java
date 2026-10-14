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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.PostgresqlSkillPackageChangeNotifier;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Skill Package 生产写入口的事务内 {@code skill_package_changed} 通知验证。
 *
 * <p>意图：真实 Java 写入口（仓储 insert/update/delete 与 {@code SkillCatalogService} 的 {@code NOT_SUPPORTED}
 * 外层 + {@code SkillCatalogWrites.REQUIRES_NEW} 内层事务）在成功写事实的同一事务内发布失效提示，由独立非池化 PostgreSQL LISTEN
 * 连接观测。覆盖提交后投递、 未提交不可见、回滚静默、CAS 0 行与未变化编辑静默、无事务调用在写行前拒绝。
 */
class SkillPackageChangeNotificationIntegrationTest extends PostgresSpringTestSupport {

  private static final String PACKAGE = "notify-package";
  private static final String REPOSITORY_URL = "https://git.example.com/notify-package.git";
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @MockitoBean private SkillGitCache skillGitCache;
  @Autowired private SkillCatalogService skillCatalogService;
  @Autowired private SkillPackageRepository skillPackageRepository;
  @Autowired private PostgresqlSkillPackageChangeNotifier notifier;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 插入/版本变化/删除都在提交后投递一次；未提交不可见，回滚静默且不改变权威事实。 */
  @Test
  void committedWriteNotifiesAndUncommittedOrRolledBackIsSilent() throws Exception {
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      // 未提交的插入对其它连接不可见。
      tx.executeWithoutResult(
          status -> {
            assertTrue(skillPackageRepository.insertPackage(packageRow()));
            assertNoNotification(pg);
          });
      assertNotification(pg, PACKAGE);
      assertNoNotification(pg);

      // 回滚的版本变更绝不投递，也不改变权威 version/branch。
      tx.executeWithoutResult(
          status -> {
            SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
            current.setBranch("release");
            assertTrue(skillPackageRepository.updatePackage(current, current.getVersion()));
            status.setRollbackOnly();
          });
      assertNoNotification(pg);
      SkillPackage rolledBack = skillPackageRepository.getPackage(PACKAGE);
      assertEquals(0L, rolledBack.getVersion());
      assertEquals("main", rolledBack.getBranch());

      // 提交的版本变更投递一次。
      tx.executeWithoutResult(
          status -> {
            SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
            current.setBranch("release");
            assertTrue(skillPackageRepository.updatePackage(current, current.getVersion()));
          });
      assertNotification(pg, PACKAGE);
      assertEquals(1L, skillPackageRepository.getPackage(PACKAGE).getVersion());

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
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> assertTrue(skillPackageRepository.insertPackage(packageRow())));

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);

      // 陈旧 CAS：0 行受影响，version 未推进，静默。
      tx.executeWithoutResult(
          status -> {
            SkillPackage current = skillPackageRepository.getPackage(PACKAGE);
            assertFalse(skillPackageRepository.updatePackage(current, current.getVersion() + 5));
          });
      assertNoNotification(pg);

      // 编辑可编辑字段但事实未变化：服务直接返回，不写行、不通知。
      SkillPackageEditDTO unchanged = new SkillPackageEditDTO();
      unchanged.setExpectedVersion("0");
      unchanged.setDescription("notify package");
      unchanged.setBranch("main");
      skillCatalogService.editPackage(PACKAGE, unchanged);
      assertNoNotification(pg);
      assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).getVersion());

      // 陈旧版本编辑：在写行前就 conflict，静默。
      SkillPackageEditDTO stale = new SkillPackageEditDTO();
      stale.setExpectedVersion("9");
      stale.setDescription("changed");
      stale.setBranch("release");
      assertThrows(
          AiVersionConflictException.class, () -> skillCatalogService.editPackage(PACKAGE, stale));
      assertNoNotification(pg);
      assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).getVersion());
    }
  }

  /** 外层 NOT_SUPPORTED + 内层 REQUIRES_NEW：Create 的通知必须落在内层实际写事务内并在提交后投递。 */
  @Test
  void createPackagePublishesInsideInnerRequiresNewTransaction() throws Exception {
    when(skillGitCache.resolveBranchHead(REPOSITORY_URL, "main", null)).thenReturn(COMMIT);
    when(skillGitCache.scanManifest(PACKAGE, COMMIT))
        .thenReturn(List.of(new SkillManifestEntry("dev", "developer skill")));

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);

      SkillPackageCreateDTO create = new SkillPackageCreateDTO();
      create.setPackageName(PACKAGE);
      create.setDescription("notify package");
      create.setRepositoryUrl(REPOSITORY_URL);
      create.setBranch("main");

      // 外层方法 NOT_SUPPORTED：若通知被放到无事务路径，requireTransaction 会直接拒绝。
      skillCatalogService.createPackage(create);

      assertNotification(pg, PACKAGE);
      assertNoNotification(pg);
      assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).getVersion());
    }
  }

  /** 无事务直接调用仓储写路径：在执行 SQL 前拒绝，权威行原样不变，listener 静默。 */
  @Test
  void repositoryWriteWithoutTransactionIsRejectedBeforeAnyWrite() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> assertTrue(skillPackageRepository.insertPackage(packageRow())));
    SkillPackage before = skillPackageRepository.getPackage(PACKAGE);

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + EnvironmentSkillSyncOrchestrator.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);

      // 三个写入口都在执行 SQL 前拒绝，未产生 autocommit 写入。
      assertThrows(
          IllegalStateException.class, () -> skillPackageRepository.insertPackage(packageRow()));
      assertThrows(
          IllegalStateException.class,
          () -> {
            SkillPackage changed = skillPackageRepository.getPackage(PACKAGE);
            changed.setBranch("release");
            skillPackageRepository.updatePackage(changed, changed.getVersion());
          });
      assertThrows(
          IllegalStateException.class,
          () -> skillPackageRepository.deletePackage(PACKAGE, before.getVersion()));

      SkillPackage after = skillPackageRepository.getPackage(PACKAGE);
      assertNotNull(after);
      assertEquals(before.getVersion(), after.getVersion());
      assertEquals(before.getBranch(), after.getBranch());
      assertNoNotification(pg);
    }
  }

  /** 未开启真实事务直接发布必须拒绝，避免 autocommit 提前发信号。 */
  @Test
  void notificationRequiresActiveTransaction() {
    assertThrows(IllegalStateException.class, () -> notifier.packageChanged(PACKAGE));
  }

  private static SkillPackage packageRow() {
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(PACKAGE);
    skillPackage.setDescription("notify package");
    skillPackage.setRepositoryUrl(REPOSITORY_URL);
    skillPackage.setBranch("main");
    skillPackage.setCurrentCommit(COMMIT);
    skillPackage.setObservedHeadCommit(COMMIT);
    skillPackage.setSkills(List.of(new SkillManifestEntry("dev", "developer skill")));
    skillPackage.setVersion(0L);
    return skillPackage;
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
