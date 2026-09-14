package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;
import fun.fengwk.kkstudio.platform.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationHandler;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

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
      IssueControllerDispatcher issueControllerDispatcher,
      CanvasFunctionDispatcher canvasFunctionDispatcher,
      EnvironmentOperationDispatcher environmentOperationDispatcher,
      ThreadVersionHub threadVersionHub,
      CanvasVersionHub canvasVersionHub,
      ProjectInvalidationHub projectInvalidationHub,
      SystemSettingsChangeHandler systemSettingsChangeHandler,
      PostgresqlRealtimeEventSource realtimeEventSource,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new PostgresqlNotificationLoop(
        dataSource,
        List.of(
            // 1. 任务调度唤醒：广播提示有新的 Harness 任务 (THREAD/MODEL/TOOL) 就绪，唤醒调度器执行 drain 排空；调度器通过 FOR UPDATE
            // SKIP LOCKED 并发抢占，无锁竞争
            new PostgresqlNotificationHandler(
                HarnessWorkDispatcher.CHANNEL, ignored -> dispatcher.wake(), dispatcher::wake),
            // 2. Issue Controller 任务调度唤醒：广播提示有新的 Issue Controller 任务就绪，唤醒调度器执行 drain 排空
            new PostgresqlNotificationHandler(
                IssueControllerDispatcher.CHANNEL,
                ignored -> issueControllerDispatcher.wake(),
                issueControllerDispatcher::wake),
            // 3. 画布函数唤醒：广播提示有新的 Canvas 异步函数计算任务就绪，唤醒画布函数调度器
            new PostgresqlNotificationHandler(
                CanvasFunctionDispatcher.CHANNEL,
                ignored -> canvasFunctionDispatcher.wake(),
                canvasFunctionDispatcher::wake),
            // 4. 环境操作调度唤醒：广播提示有新的 EnvironmentOperation (REFRESH/INSTALL/UPDATE) 就绪，唤醒环境操作调度器
            new PostgresqlNotificationHandler(
                EnvironmentOperationDispatcher.CHANNEL,
                ignored -> environmentOperationDispatcher.wake(),
                environmentOperationDispatcher::wake),
            // 5. 会话版本失效：Thread 版本号推进，通知 WebSocket 向前端广播版本事件以触发快照对账
            new PostgresqlNotificationHandler(
                ThreadVersionHub.CHANNEL,
                threadVersionHub::onNotification,
                threadVersionHub::broadcastResync),
            // 6. 画布版本失效：Canvas 文档版本号推进，通知 WebSocket 向前端广播版本事件以触发图谱更新
            new PostgresqlNotificationHandler(
                CanvasVersionHub.CHANNEL,
                canvasVersionHub::onNotification,
                canvasVersionHub::broadcastResync),
            // 7. Project/Issue 失效：数据库事实提交后按 projectId 提示浏览器回读权威 Snapshot
            new PostgresqlNotificationHandler(
                ProjectInvalidationHub.CHANNEL,
                projectInvalidationHub::onNotification,
                projectInvalidationHub::broadcastResync),
            // 8. 系统设置同步：集群任一节点修改全局设置提交后，广播通知所有节点原子回读最新快照
            new PostgresqlNotificationHandler(
                SystemSettingsChangeHandler.CHANNEL,
                systemSettingsChangeHandler::onNotification,
                systemSettingsChangeHandler::onResync),
            // 9. 流式增量推送：大模型生成的文本 Delta 与工具局部输出，直接经由通道推送到前端，不落库
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
      ProjectInvalidationHub projectInvalidationHub,
      ApplicationEventSettings settings) {
    return new ApplicationEventHub(
        threadVersionSource,
        realtimeSource,
        canvasVersionSource,
        projectInvalidationHub,
        settings.queueCapacity());
  }

  /** 全应用事件连接共享一个 heartbeat scheduler；每次 tick 只做异步发送队列入队。 */
  @Bean(name = "applicationEventHeartbeatScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService applicationEventHeartbeatScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("application-event-heartbeat").daemon(true).factory());
  }
}
