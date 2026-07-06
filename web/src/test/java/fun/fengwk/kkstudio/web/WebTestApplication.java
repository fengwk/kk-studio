package fun.fengwk.kkstudio.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * @author fengwk
 */
@SpringBootApplication
@Import(AgentRuntimeWebTestConfiguration.class)
public class WebTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebTestApplication.class, args);
  }
}
