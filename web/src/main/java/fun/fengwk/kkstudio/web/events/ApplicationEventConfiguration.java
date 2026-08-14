package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventSource;

/** 事件通道组合：严格帧 codec 与传输无关的 {@link ApplicationEventHub}。 */
@Configuration(proxyBeanMethods = false)
public class ApplicationEventConfiguration {

  @Bean
  public EventFrameCodec eventFrameCodec() {
    return new EventFrameCodec(new RealtimeEventJsonCodec());
  }

  @Bean(destroyMethod = "close")
  public ApplicationEventHub applicationEventHub(
      ThreadRevisionEventSource revisionSource,
      RealtimeEventSource realtimeSource,
      CanvasVersionEventSource versionSource) {
    return new ApplicationEventHub(revisionSource, realtimeSource, versionSource);
  }
}
