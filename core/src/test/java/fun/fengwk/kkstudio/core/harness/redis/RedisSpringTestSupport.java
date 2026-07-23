package fun.fengwk.kkstudio.core.harness.redis;

import static fun.fengwk.kkstudio.core.harness.redis.RedisRuntimeSupport.REDIS;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.core.CoreTestApplication;

/**
 * 共享 Spring 上下文，把 Redis bean 指向进程级单例 Testcontainer。{@code workers-enabled=false} 关闭 worker
 * lifecycle（与 PostgreSQL 测试保持一致）。
 */
@SpringBootTest(classes = CoreTestApplication.class)
public abstract class RedisSpringTestSupport {

  private static final String WORKERS_DISABLED = "false";

  @DynamicPropertySource
  static void overrideRedisHost(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> WORKERS_DISABLED);
  }
}
