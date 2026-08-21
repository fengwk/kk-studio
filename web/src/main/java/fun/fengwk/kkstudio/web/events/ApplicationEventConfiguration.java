package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventSource;

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
   * 内部观察者（TaskTool / HarnessOneShotService）的 Thread change 源：复用 {@link ThreadRevisionEventSource}
   * 的原子订阅与 resync 语义，只保留纯 wake 信号。
   */
  @Bean
  public HarnessThreadChangeSource harnessThreadChangeSource(
      ThreadRevisionEventSource revisionSource) {
    return new WebHarnessThreadChangeSource(revisionSource);
  }

  @Bean(destroyMethod = "close")
  public ApplicationEventHub applicationEventHub(
      ThreadRevisionEventSource revisionSource,
      RealtimeEventSource realtimeSource,
      CanvasVersionEventSource versionSource,
      ApplicationEventSettings settings) {
    return new ApplicationEventHub(
        revisionSource, realtimeSource, versionSource, settings.queueCapacity());
  }

  /** 全应用事件连接共享一个 heartbeat scheduler；每次 tick 只做异步发送队列入队。 */
  @Bean(name = "applicationEventHeartbeatScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService applicationEventHeartbeatScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("application-event-heartbeat").daemon(true).factory());
  }
}
