package fun.fengwk.kkstudio.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Core module Spring Boot test entry.
 *
 * <p>Core is not the Harness composition root: the production {@code EnvironmentReadyListener}
 * lives in the web module, so test contexts import {@link CoreHarnessTestConfiguration} to keep the
 * environment gateway startable with a no-op READY bridge.
 */
@SpringBootApplication
@Import(CoreHarnessTestConfiguration.class)
public class CoreTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreTestApplication.class, args);
  }
}
