package fun.fengwk.kkstudio.core.harness.redis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

/**
 * 装配 Redis activation notifier 与 realtime event sink。{@link HarnessRedisProperties} 总是注册并在 adapter
 * 构造时校验。Redis template 通过 {@link ObjectProvider} 延迟解析，因此无 Redis bean 的上下文仍能启动；实际调用会抛出
 * RuntimeException，由 Runtime 调用方隔离。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRedisProperties.class)
public class HarnessRedisConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ExecutionTargetJsonCodec executionTargetJsonCodec() {
    return new ExecutionTargetJsonCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public RealtimeEventJsonCodec realtimeEventJsonCodec() {
    return new RealtimeEventJsonCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public ActivationNotifier redisActivationNotifier(
      ObjectProvider<StringRedisTemplate> stringRedisTemplate,
      HarnessRedisProperties properties,
      ExecutionTargetJsonCodec targetCodec) {
    return new RedisActivationNotifier(stringRedisTemplate::getObject, properties, targetCodec);
  }

  @Bean
  @ConditionalOnMissingBean
  public RealtimeEventSink redisRealtimeEventSink(
      ObjectProvider<StringRedisTemplate> stringRedisTemplate,
      HarnessRedisProperties properties,
      RealtimeEventJsonCodec eventCodec) {
    return new RedisRealtimeEventSink(stringRedisTemplate::getObject, properties, eventCodec);
  }

  @Bean
  @ConditionalOnMissingBean
  public RedisRealtimeEventTail redisRealtimeEventTail(
      ObjectProvider<StringRedisTemplate> stringRedisTemplate, HarnessRedisProperties properties) {
    return new RedisRealtimeEventTail(stringRedisTemplate::getObject, properties);
  }
}
