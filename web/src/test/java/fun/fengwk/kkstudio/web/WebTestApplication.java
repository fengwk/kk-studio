package fun.fengwk.kkstudio.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Web module Spring Boot test entry.
 *
 * <p>WebPostgresTestSupport pins a fixed Snowflake worker id so suites remain stable without a
 * production Redis worker lease; Redis-backed realtime adapters still receive Testcontainers when
 * exercised.
 */
@SpringBootApplication
@Import(HarnessWebTestConfiguration.class)
public class WebTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebTestApplication.class, args);
  }
}
