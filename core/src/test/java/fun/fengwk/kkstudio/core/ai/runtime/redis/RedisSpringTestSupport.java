package fun.fengwk.kkstudio.core.ai.runtime.redis;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.POSTGRES;
import static fun.fengwk.kkstudio.core.ai.runtime.redis.RedisRuntimeSupport.REDIS;

import org.postgresql.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.core.CoreTestApplication;

/**
 * Shared Spring context for Redis adapter tests. Redis points at a process-level Testcontainer;
 * PostgreSQL is also wired so CoreTestApplication can start without the 127.0.0.1:1 placeholder.
 * Workers stay disabled.
 */
@SpringBootTest(classes = CoreTestApplication.class)
public abstract class RedisSpringTestSupport {

  private static final String WORKERS_DISABLED = "false";
  private static final String SQL_INIT_NEVER = "never";

  @DynamicPropertySource
  static void overrideRuntimeInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
    registry.add("spring.sql.init.mode", () -> SQL_INIT_NEVER);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> WORKERS_DISABLED);
  }
}
