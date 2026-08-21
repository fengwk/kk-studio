package fun.fengwk.kkstudio.core.systemsettings;

import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** 系统设置跨节点唤醒的 Redis 适配：PUBLISH 走共享 {@link StringRedisTemplate}；SUBSCRIBE 使用 listener 持有的独占连接。 */
@Configuration(proxyBeanMethods = false)
public class SystemSettingsRedisConfiguration {

  @Bean
  public SystemSettingsChangePublisher systemSettingsChangePublisher(
      StringRedisTemplate stringRedisTemplate) {
    return new RedisSystemSettingsChangePublisher(
        stringRedisTemplate, RedisSystemSettingsChangeListener.CHANNEL);
  }

  @Bean(destroyMethod = "close")
  public RedisSystemSettingsChangeListener redisSystemSettingsChangeListener(
      RedisConnectionDetails connectionDetails,
      SystemSettingsRepository repository,
      SystemSettingsSnapshot snapshot) {
    Duration retryDelay =
        Duration.ofMillis(snapshot.get().advanced().redisRealtimeRetryDelayMillis());
    return RedisSystemSettingsChangeListener.owning(
        exclusiveFactory(connectionDetails),
        retryDelay,
        new SystemSettingsSnapshotRefresh(repository, snapshot));
  }

  private static LettuceConnectionFactory exclusiveFactory(
      RedisConnectionDetails connectionDetails) {
    RedisConnectionDetails.Standalone standaloneDetails = connectionDetails.getStandalone();
    RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
    standalone.setHostName(standaloneDetails.getHost());
    standalone.setPort(standaloneDetails.getPort());
    standalone.setDatabase(standaloneDetails.getDatabase());
    standalone.setUsername(connectionDetails.getUsername());
    standalone.setPassword(RedisPassword.of(connectionDetails.getPassword()));
    LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(standalone);
    connectionFactory.setShareNativeConnection(false);
    connectionFactory.afterPropertiesSet();
    return connectionFactory;
  }
}
