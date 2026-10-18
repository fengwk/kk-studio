package fun.fengwk.kkstudio.web;

import fun.fengwk.convention4j.springboot.starter.json.JacksonAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author fengwk
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class WebApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebApplication.class, args);
  }
}
