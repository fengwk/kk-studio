package fun.fengwk.kkstudio.web.events;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalRouteSource;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 事件通道组合：严格帧 codec 与传输无关的 {@link ApplicationEventHub}。 */
@Configuration(proxyBeanMethods = false)
public class ApplicationEventConfiguration {

  @Bean
  public EventFrameCodec eventFrameCodec() {
    return new EventFrameCodec(new RealtimeEventJsonCodec());
  }

  /**
   * 浏览器事件通道的软策略快照：读取共享启动快照 {@link SystemSettingsSnapshot} 的 SystemSettings.Advanced {@code
   * applicationEvent*} 字段（装配期一次 DB 读取，DB 变更需重启生效），供 Hub 缓冲上限、发送队列上界、send timeout 与 heartbeat 间隔使用。
   */
  @Bean
  public ApplicationEventSettings applicationEventSettings(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ApplicationEventSettings(
        advanced.applicationEventQueueCapacity(),
        advanced.applicationEventMaxBytes(),
        advanced.applicationEventSendTimeoutMillis(),
        advanced.applicationEventHeartbeatIntervalMillis());
  }

  /**
   * 内部观察者（TaskTool / HarnessOneShotService）的 Thread change 源：复用 {@link ThreadVersionEventSource}
   * 的原子订阅与 resync 语义，只保留纯 wake 信号。
   */
  @Bean
  public HarnessThreadChangeSource harnessThreadChangeSource(
      ThreadVersionEventSource versionSource) {
    return new WebHarnessThreadChangeSource(versionSource);
  }

  @Bean(destroyMethod = "close")
  public ApplicationEventHub applicationEventHub(
      ThreadVersionEventSource threadVersionSource,
      RealtimeEventSource realtimeSource,
      CanvasVersionEventSource canvasVersionSource,
      ProjectInvalidationHub projectInvalidationHub,
      ExecutionTreeChangeHub executionTreeChangeHub,
      InteractionChangeHub interactionChangeHub,
      EnvironmentChangeHub environmentChangeHub,
      ApplicationEventSettings settings) {
    return new ApplicationEventHub(
        threadVersionSource,
        realtimeSource,
        canvasVersionSource,
        projectInvalidationHub,
        executionTreeChangeHub,
        interactionChangeHub,
        environmentChangeHub,
        settings.queueCapacity());
  }

  /** 全应用事件连接共享一个 heartbeat scheduler；每次 tick 只做异步发送队列入队。 */
  @Bean(name = "applicationEventHeartbeatScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService applicationEventHeartbeatScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("application-event-heartbeat").daemon(true).factory());
  }

  /** 全应用事件连接共享的出站 executor：只驱动共享 outbox 的 drain 批次与连接收尾，不拥有任何每连接线程。 */
  @Bean(name = "applicationEventSendExecutor", destroyMethod = "shutdown")
  public ExecutorService applicationEventSendExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * 浏览器 shell 网关：唯一经 NotificationBus 的 shell.command/shell.event 两个 topic 收发，用窄 READY 路由端口选择
   * owner，owner 侧调用唯一 {@link EnvironmentDaemonServer#sendShell}。
   */
  @Bean(destroyMethod = "close")
  public ShellGateway shellGateway(
      NotificationBus notificationBus,
      EnvironmentTerminalRouteSource environmentTerminalRouteSource,
      EnvironmentDaemonServer environmentDaemonServer,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      @Qualifier("applicationEventSendExecutor") ExecutorService applicationEventSendExecutor) {
    return new ShellGateway(
        notificationBus,
        environmentTerminalRouteSource,
        environmentDaemonServer,
        nodeInstanceId,
        applicationEventSendExecutor);
  }
}
