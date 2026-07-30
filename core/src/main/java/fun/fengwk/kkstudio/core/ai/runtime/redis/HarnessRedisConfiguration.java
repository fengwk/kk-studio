package fun.fengwk.kkstudio.core.ai.runtime.redis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.realtime.HarnessRealtimeEventTail;
import fun.fengwk.kkstudio.core.ai.runtime.realtime.stream.HarnessRealtimeStreamPolicyService;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

/**
 * Redis realtime projection adapter composition.
 *
 * <p>{@link HarnessRedisProperties} always registers and adapters lazily resolve the Redis template
 * through {@link ObjectProvider}. Redis carries only lossy realtime projection; durable Harness
 * activation is owned by the PostgreSQL execution-target dispatcher.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRedisProperties.class)
public class HarnessRedisConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public RealtimeEventJsonCodec realtimeEventJsonCodec() {
    return new RealtimeEventJsonCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public RealtimeEventSink redisRealtimeEventSink(
      ObjectProvider<StringRedisTemplate> stringRedisTemplate,
      HarnessRedisProperties properties,
      RealtimeEventJsonCodec eventCodec,
      HarnessRealtimeStreamPolicyService realtimeStreamPolicyService) {
    return new RedisRealtimeEventSink(
        stringRedisTemplate::getObject,
        properties,
        eventCodec,
        realtimeStreamPolicyService::resolveMaxLength);
  }

  @Bean
  @ConditionalOnMissingBean
  public HarnessRealtimeEventTail redisRealtimeEventTail(
      ObjectProvider<StringRedisTemplate> stringRedisTemplate, HarnessRedisProperties properties) {
    return new RedisRealtimeEventTail(stringRedisTemplate::getObject, properties);
  }
}
