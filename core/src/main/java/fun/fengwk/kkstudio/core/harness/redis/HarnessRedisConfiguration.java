package fun.fengwk.kkstudio.core.harness.redis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import fun.fengwk.kkstudio.core.harness.realtime.HarnessRealtimeEventTail;
import fun.fengwk.kkstudio.core.harness.realtime.stream.HarnessRealtimeStreamPolicyService;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.concurrent.Executor;

/**
 * 装配 Redis activation notifier、subscriber 与 realtime event adapter。{@link HarnessRedisProperties}
 * 总是注册并在 adapter 构造时校验。outbound adapter 通过 {@link ObjectProvider} 延迟解析 Redis template， 因此可在无 Redis
 * bean 的上下文中构造；但 {@code workers-enabled=true}（或缺省）时，activation subscriber/container 需要 {@code
 * RedisConnectionFactory}，Redis 是必需运行时依赖。
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
  public RedisExecutionTargetDispatcher redisExecutionTargetDispatcher(
      ThreadKick threadKick,
      ModelWorker modelWorker,
      @Qualifier("modelWorkerScheduler") Executor modelWorkerScheduler,
      ToolWorker toolWorker,
      @Qualifier("toolWorkerScheduler") Executor toolWorkerScheduler) {
    return new RedisExecutionTargetDispatcher(
        threadKick, modelWorker, modelWorkerScheduler, toolWorker, toolWorkerScheduler);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RedisExecutionTargetSubscriber redisExecutionTargetSubscriber(
      ExecutionTargetJsonCodec targetCodec, RedisExecutionTargetDispatcher dispatcher) {
    return new RedisExecutionTargetSubscriber(targetCodec, dispatcher);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RedisMessageListenerContainer harnessActivationRedisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RedisExecutionTargetSubscriber subscriber,
      HarnessRedisProperties properties) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(subscriber, new ChannelTopic(properties.requireSignalChannel()));
    return container;
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
