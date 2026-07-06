package fun.fengwk.kkstudio.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * @author fengwk
 */
@SpringBootApplication
@Import(AgentRuntimeTestConfiguration.class)
public class CoreTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreTestApplication.class, args);
  }
}
