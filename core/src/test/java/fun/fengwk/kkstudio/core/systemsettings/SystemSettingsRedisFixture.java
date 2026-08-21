package fun.fengwk.kkstudio.core.systemsettings;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 进程级单例 {@code redis:7-alpine}，供 system settings Pub/Sub 集成测试使用。
 *
 * <p>连接不共享 native connection，使 PUBLISH 与 SUBSCRIBE 各占独立连接。Docker 不可用时容器启动直接失败。
 */
final class SystemSettingsRedisFixture {

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .waitingFor(Wait.forListeningPort());

  private static final LettuceConnectionFactory CONNECTION_FACTORY;
  private static final StringRedisTemplate TEMPLATE;

  static {
    REDIS.start();
    CONNECTION_FACTORY =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    CONNECTION_FACTORY.setShareNativeConnection(false);
    CONNECTION_FACTORY.afterPropertiesSet();
    TEMPLATE = new StringRedisTemplate(CONNECTION_FACTORY);
    TEMPLATE.afterPropertiesSet();
  }

  private SystemSettingsRedisFixture() {}

  static StringRedisTemplate template() {
    return TEMPLATE;
  }

  static LettuceConnectionFactory connectionFactory() {
    return CONNECTION_FACTORY;
  }
}
