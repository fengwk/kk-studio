package fun.fengwk.kkstudio.core.ai.runtime.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 进程级单例 Redis Testcontainer，复用 {@code redis:7.4-alpine}。Docker 不可用时容器启动失败并让 集成测试红屏，不静默跳过；与 {@link
 * fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport} 共享同一种
 * "process-wide container" 模式，避免 Spring context cache stale。
 */
public abstract class RedisRuntimeSupport {

  @SuppressWarnings("resource")
  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(6379)
          .waitingFor(Wait.forListeningPort());

  static {
    REDIS.start();
  }

  /** 当前 Redis 容器的主机名或 IP。 */
  public static String redisHost() {
    return REDIS.getHost();
  }

  /** 当前 Redis 容器的端口。 */
  public static int redisPort() {
    return REDIS.getMappedPort(6379);
  }

  private RedisRuntimeSupport() {}
}
