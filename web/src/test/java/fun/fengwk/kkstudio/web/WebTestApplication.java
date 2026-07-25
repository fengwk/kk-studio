package fun.fengwk.kkstudio.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Web module Spring Boot test entry.
 *
 * <p>Postgres-backed integration suites pin durable ids through PostgreSQL sequences; Redis-backed
 * realtime adapters still receive Testcontainers when exercised.
 */
@SpringBootApplication
@Import(HarnessWebTestConfiguration.class)
public class WebTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebTestApplication.class, args);
  }
}
