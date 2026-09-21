package fun.fengwk.kkstudio.web.events.postgresql;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/**
 * Skill Package 发布通知到 Skill 同步编排器的端到端接线测试。
 *
 * <p>意图：数据库提交后 {@code skill_package_changed} 通知必须带 package 名抵达编排器（该环境无 READY Environment
 * 时不做任何同步）；读取自身不触发通知。这条链路决定「HTTP 发布立即返回、同步异步收敛」，因此 channel 名与 payload 语义必须由真实 PostgreSQL
 * 触发器证明，而不是只靠常量重命名守护。
 */
class SkillPackageChangedNotificationIntegrationTest extends WebPostgresTestSupport {

  @MockitoBean private EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void packageChangeNotificationReachesOrchestratorWithPackageName() {
    jdbcTemplate.update(
        "insert into skill_package (package_name, repository_url, branch, current_commit)"
            + " values ('notify-package', 'https://git.example.com/notify-package.git', 'main',"
            + " '0123456789abcdef0123456789abcdef01234567')");

    // 通知由 loop 线程异步派发：编排器收到的唯一事实就是 package 名。
    verify(environmentSkillSyncOrchestrator, timeout(10_000)).onPackageChanged("notify-package");
    // 一次提交只发一次通知。
    verify(environmentSkillSyncOrchestrator, after(1_000).times(1))
        .onPackageChanged("notify-package");
  }
}
