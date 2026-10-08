package fun.fengwk.kkstudio.web.events;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.canvas.notification.CanvasNotifications;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.realtime.BusRealtimeEventSource;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.notification.NotificationLimits;
import fun.fengwk.kkstudio.notification.NotificationTransactionManager;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.project.notification.ProjectNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 全应用唯一通知总线的组合根：登记全部领域 topic、以共享启动快照的节奏装配唯一传输，并把全部静态订阅绑定到既有 Hub 与调度器。
 *
 * <p>发布与订阅共用同一 topic 实例；本组合根不提供第二套通道、动态主题或按需回退。传输只负责把提示送到各节点的本地订阅，权威事实仍由各 Hub
 * 回读数据库。绑定失败会连同总线一起让上下文启动失败，绝不静默降级为「无通知」。
 */
@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

  /**
   * 全部领域固定 topic 的唯一注册表。
   *
   * <p>发布侧（各领域 notifier / 事务写入口）与订阅侧（{@link NotificationSubscriptions}）都必须使用这里的同一实例；总线在绑定校验时按
   * identity 拒绝未登记的 topic。
   */
  private static final List<NotificationTopic<?>> TOPICS =
      List.of(
          HarnessNotifications.WORK_AVAILABLE,
          HarnessNotifications.THREAD_VERSION,
          HarnessNotifications.THREAD_TREE,
          HarnessNotifications.TOOL_INTERACTION,
          HarnessNotifications.REALTIME,
          CanvasNotifications.REVISION,
          CanvasNotifications.FUNCTION_WORK,
          ProjectNotifications.ISSUE_CHANGED,
          ProjectNotifications.WORK_DUE,
          PlatformNotifications.SETTINGS_CHANGED,
          PlatformNotifications.SKILL_PACKAGE_CHANGED,
          PlatformNotifications.ENVIRONMENT_CHANGED);

  /**
   * 全应用唯一的 physical transaction manager：沿用 Spring Boot 默认的 {@link
   * org.springframework.jdbc.support.JdbcTransactionManager}
   * 提交/回滚异常翻译语义，并在每个新同步事务起始登记最高优先级的阶段标记，使总线能在 afterCommit 线程上拒绝发布——否则该发布只会注册一个永远不提交的批次并被静默丢弃。业务
   * claim/CAS 与既有隔离级别行为不变。
   */
  @Bean
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new NotificationTransactionManager(dataSource);
  }

  /** 发布、订阅信箱与 PG 重组共用的唯一资源预算；realtime 超限降级等阈值也取自这里。 */
  @Bean
  public NotificationLimits notificationLimits() {
    return NotificationLimits.defaults();
  }

  /**
   * 唯一通知总线：订阅本地即时投递，跨节点经 PostgreSQL 在同一物理事务内投递；进程启动即建立传输。
   *
   * <p>轮询与重连退避读取共享启动快照 {@link SystemSettingsSnapshot} 的 SystemSettings.Advanced（DB
   * 变更需重启生效），与既有部署软策略保持同一 来源，不再有第二组独立默认值。
   */
  @Bean(initMethod = "start", destroyMethod = "close")
  public DefaultNotificationBus notificationBus(
      DataSource dataSource,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      NotificationLimits notificationLimits,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new DefaultNotificationBus(
        dataSource,
        nodeInstanceId,
        TOPICS,
        notificationLimits,
        Duration.ofMillis(advanced.notificationPollMillis()),
        Duration.ofMillis(advanced.notificationReconnectBackoffMillis()));
  }

  /** 全部领域订阅的唯一绑定宿主；随上下文关闭一并撤销。 */
  @Bean(destroyMethod = "close")
  public NotificationSubscriptions notificationSubscriptions(
      DefaultNotificationBus notificationBus,
      HarnessWorkDispatcher harnessWorkDispatcher,
      IssueControllerDispatcher issueControllerDispatcher,
      CanvasFunctionDispatcher canvasFunctionDispatcher,
      ThreadVersionHub threadVersionHub,
      CanvasVersionHub canvasVersionHub,
      ProjectInvalidationHub projectInvalidationHub,
      SystemSettingsChangeHandler systemSettingsChangeHandler,
      BusRealtimeEventSource realtimeEventSource,
      EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator,
      ExecutionTreeChangeHub executionTreeChangeHub,
      InteractionChangeHub interactionChangeHub,
      EnvironmentChangeHub environmentChangeHub) {
    return NotificationSubscriptions.bind(
        notificationBus,
        harnessWorkDispatcher,
        issueControllerDispatcher,
        canvasFunctionDispatcher,
        threadVersionHub,
        canvasVersionHub,
        projectInvalidationHub,
        systemSettingsChangeHandler,
        realtimeEventSource,
        environmentSkillSyncOrchestrator,
        executionTreeChangeHub,
        interactionChangeHub,
        environmentChangeHub);
  }
}
