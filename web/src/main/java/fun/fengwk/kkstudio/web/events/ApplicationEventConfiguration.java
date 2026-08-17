package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.HarnessThreadChangeSource;
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
      CanvasVersionEventSource versionSource) {
    return new ApplicationEventHub(revisionSource, realtimeSource, versionSource);
  }

  /** 全应用事件连接共享一个 heartbeat scheduler；每次 tick 只做异步发送队列入队。 */
  @Bean(name = "applicationEventHeartbeatScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService applicationEventHeartbeatScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("application-event-heartbeat").daemon(true).factory());
  }
}
