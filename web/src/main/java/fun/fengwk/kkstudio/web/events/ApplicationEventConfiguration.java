package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationHandler;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.List;
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

  /** 全应用共享一个专用 PostgreSQL connection，固定监听全部低延迟通知 channel。 */
  @Bean(destroyMethod = "close")
  public PostgresqlNotificationLoop postgresqlNotificationLoop(
      DataSource dataSource,
      HarnessWorkDispatcher dispatcher,
      ThreadVersionHub threadVersionHub,
      CanvasVersionHub canvasVersionHub,
      SystemSettingsChangeHandler systemSettingsChangeHandler,
      PostgresqlRealtimeEventSource realtimeEventSource,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new PostgresqlNotificationLoop(
        dataSource,
        List.of(
            new PostgresqlNotificationHandler(
                "harness_runtime_work", ignored -> dispatcher.wake(), dispatcher::wake),
            new PostgresqlNotificationHandler(
                ThreadVersionHub.CHANNEL,
                threadVersionHub::onNotification,
                threadVersionHub::broadcastResync),
            new PostgresqlNotificationHandler(
                CanvasVersionHub.CHANNEL,
                canvasVersionHub::onNotification,
                canvasVersionHub::broadcastResync),
            new PostgresqlNotificationHandler(
                "system_settings_changed",
                systemSettingsChangeHandler::onNotification,
                systemSettingsChangeHandler::onResync),
            new PostgresqlNotificationHandler(
                PostgresqlRealtimeEventSource.CHANNEL,
                realtimeEventSource::onNotification,
                realtimeEventSource::onResync)),
        Duration.ofMillis(advanced.postgresqlWorkNotificationPollMillis()),
        Duration.ofMillis(advanced.postgresqlWorkReconnectBackoffMillis()));
  }

  @Bean(destroyMethod = "close")
  public ApplicationEventHub applicationEventHub(
      ThreadVersionEventSource threadVersionSource,
      RealtimeEventSource realtimeSource,
      CanvasVersionEventSource canvasVersionSource,
      ApplicationEventSettings settings) {
    return new ApplicationEventHub(
        threadVersionSource, realtimeSource, canvasVersionSource, settings.queueCapacity());
  }

  /** 全应用事件连接共享一个 heartbeat scheduler；每次 tick 只做异步发送队列入队。 */
  @Bean(name = "applicationEventHeartbeatScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService applicationEventHeartbeatScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("application-event-heartbeat").daemon(true).factory());
  }
}
