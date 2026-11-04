package fun.fengwk.kkstudio.web.events.postgresql;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageGitSource;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill Package 发布通知到 Skill 同步编排器的端到端接线测试。
 *
 * <p>意图：真实 Java 写入口（{@code SkillPackageRepository}）提交后 {@code skill_package_changed} 通知必须带 package
 * 名抵达编排器 （该环境无 READY Environment 时不做任何同步）；读取自身不触发通知。这条链路决定「HTTP 发布立即返回、同步异步收敛」，因此 channel 名与
 * payload 语义必须由真实 PostgreSQL 写路径证明，而不是只靠常量重命名守护。
 */
class SkillPackageChangedNotificationIntegrationTest extends WebPostgresTestSupport {

  private static final String PACKAGE = "notify-package";
  private static final String REPOSITORY_URL = "https://git.example.com/notify-package.git";
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @MockitoBean private EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator;

  @Autowired private SkillPackageRepository skillPackageRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void packageChangeNotificationReachesOrchestratorWithPackageName() {
    // 真实 Java 写入口：仓储 insert 在提交后发布 skill_package_changed，payload 是 package 名。
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> skillPackageRepository.insertPackage(packageRow()));

    // 通知由 loop 线程异步派发：编排器收到的唯一事实就是 package 名。
    verify(environmentSkillSyncOrchestrator, timeout(10_000)).onPackageChanged(PACKAGE);
    // 一次提交只发一次通知。
    verify(environmentSkillSyncOrchestrator, after(1_000).times(1)).onPackageChanged(PACKAGE);
  }

  private SkillPackage packageRow() {
    UUID artifactBlobId = UUID.nameUUIDFromBytes("notify-package-blob".getBytes());
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, repeat('a', 64), 1, 'application/zip', 1, 'ACTIVE')",
        artifactBlobId);
    return new SkillPackage(
        PACKAGE,
        "notify package",
        COMMIT,
        artifactBlobId,
        List.of(new SkillManifestEntry("dev", "developer skill")),
        Instant.now(),
        0L,
        null,
        null,
        new SkillPackageGitSource(REPOSITORY_URL, "main", null, COMMIT, Instant.now(), null));
  }
}
