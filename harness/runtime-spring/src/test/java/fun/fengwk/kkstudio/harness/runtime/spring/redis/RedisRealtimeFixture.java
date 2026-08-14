package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

/**
 * 进程级单例 {@code redis:7-alpine} Testcontainer 与直连 {@link StringRedisTemplate}。
 *
 * <p>连接不共享 native connection，使 blocking SUBSCRIBE、reactive listener 与并发写入各占独立连接。Docker
 * 不可用时容器启动直接失败，让集成测试红屏而不是静默跳过。
 */
final class RedisRealtimeFixture {

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
  }

  private RedisRealtimeFixture() {}

  static StringRedisTemplate template() {
    return TEMPLATE;
  }

  /** 供 reactive pub/sub listener 使用的专用连接工厂（每次取用独立连接）。 */
  static LettuceConnectionFactory connectionFactory() {
    return CONNECTION_FACTORY;
  }

  /** 删除指定 prefix 下的所有 key，保证用例之间互不干扰。 */
  static void reset(String prefix) {
    Set<String> keys = TEMPLATE.keys(prefix + "*");
    if (!keys.isEmpty()) {
      TEMPLATE.delete(keys);
    }
  }
}
