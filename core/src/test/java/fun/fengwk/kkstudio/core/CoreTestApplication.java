package fun.fengwk.kkstudio.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core module Spring Boot test entry.
 *
 * <p>Harness Redis integration tests use Testcontainers via {@code RedisSpringTestSupport}; other
 * suites pin {@code convention.snowflake-id.worker-id=0} so they do not require a live Redis worker
 * lease.
 */
@SpringBootApplication
public class CoreTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreTestApplication.class, args);
  }
}
