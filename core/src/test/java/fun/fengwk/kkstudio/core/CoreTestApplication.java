package fun.fengwk.kkstudio.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core module Spring Boot test entry.
 *
 * <p>Harness Redis integration tests use Testcontainers via {@code RedisSpringTestSupport}. Durable
 * ids are allocated from PostgreSQL sequences.
 */
@SpringBootApplication
public class CoreTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreTestApplication.class, args);
  }
}
