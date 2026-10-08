package fun.fengwk.kkstudio.platform;

import static org.mockito.Mockito.mock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.notification.CanvasNotifications;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.notification.NotificationLimits;
import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.project.controller.IssueControllerProperties;
import fun.fengwk.kkstudio.project.notification.ProjectNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Platform 测试上下文的 Harness 与通知装配基座。
 *
 * <p>生产 {@link EnvironmentSessionListener} 与 {@link NotificationBus} 由 web 组合根提供；platform
 * 不再是组合根后，测试上下文用真实 {@link DefaultNotificationBus} 支撑同节点事务提交通知断言，并用 no-op 桥接满足 {@code
 * EnvironmentDaemonGateway} 与 {@link HarnessThreadChangeSource} 的构造依赖。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformHarnessTestConfiguration {

  @Bean
  @ConditionalOnMissingBean(NotificationBus.class)
  public NotificationBus notificationBus(
      DataSource dataSource, @Qualifier("nodeInstanceId") UUID nodeInstanceId) {
    return new DefaultNotificationBus(
        dataSource,
        nodeInstanceId,
        List.of(
            PlatformNotifications.SETTINGS_CHANGED,
            PlatformNotifications.SKILL_PACKAGE_CHANGED,
            PlatformNotifications.ENVIRONMENT_CHANGED,
            ProjectNotifications.ISSUE_CHANGED,
            ProjectNotifications.WORK_DUE,
            CanvasNotifications.REVISION,
            CanvasNotifications.FUNCTION_WORK,
            HarnessNotifications.WORK_AVAILABLE,
            HarnessNotifications.THREAD_VERSION,
            HarnessNotifications.THREAD_TREE,
            HarnessNotifications.TOOL_INTERACTION,
            HarnessNotifications.REALTIME),
        NotificationLimits.defaults(),
        Duration.ofSeconds(5),
        Duration.ofSeconds(1));
  }

  @Bean
  public EnvironmentSessionListener environmentSessionListener() {
    return ignoredEnvironmentId -> {};
  }

  @Bean
  public HarnessThreadChangeSource harnessThreadChangeSource() {
    return (threadId, onChange) -> () -> {};
  }

  @Bean
  public HarnessCatalog harnessCatalog(ObjectProvider<HarnessContributor> contributors) {
    return HarnessCatalog.from(contributors.orderedStream().toList());
  }

  /**
   * 结算扫描器的锁定复核依赖 Runtime store；platform 测试上下文不装配 harness/infra，这里给出占位 bean 仅为满足装配 （platform
   * 测试从不触发结算，真实结算路径由 web 的 PostgreSQL 集成测试覆盖）。
   */
  @Bean
  @ConditionalOnMissingBean(HarnessStore.class)
  public HarnessStore platformHarnessStore() {
    return mock(HarnessStore.class);
  }

  @Bean
  public IssueControllerProperties issueControllerProperties() {
    return new IssueControllerProperties();
  }
}
