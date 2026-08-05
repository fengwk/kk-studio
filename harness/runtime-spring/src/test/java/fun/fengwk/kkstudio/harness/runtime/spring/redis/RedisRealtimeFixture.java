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
 * <p>连接不共享 native connection，使 blocking XREAD 与并发写入各占独立连接。Docker 不可用时容器启动直接失败，让 集成测试红屏而不是静默跳过。
 */
final class RedisRealtimeFixture {

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .waitingFor(Wait.forListeningPort());

  private static final StringRedisTemplate TEMPLATE;

  static {
    REDIS.start();
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    factory.setShareNativeConnection(false);
    factory.afterPropertiesSet();
    TEMPLATE = new StringRedisTemplate(factory);
  }

  private RedisRealtimeFixture() {}

  static StringRedisTemplate template() {
    return TEMPLATE;
  }

  /** 删除指定 prefix 下的所有 key，保证用例之间互不干扰。 */
  static void reset(String prefix) {
    Set<String> keys = TEMPLATE.keys(prefix + "*");
    if (!keys.isEmpty()) {
      TEMPLATE.delete(keys);
    }
  }
}
