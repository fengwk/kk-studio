package fun.fengwk.kkstudio.web;

import fun.fengwk.convention4j.springboot.starter.json.JacksonAutoConfiguration;
import fun.fengwk.convention4j.springboot.starter.result.ResultAutoConfiguration;
import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author fengwk
 */
@SpringBootApplication(
    exclude = {
      JacksonAutoConfiguration.class,
      ResultAutoConfiguration.class,
      WebExceptionResultHandlerAutoConfiguration.class
    })
public class WebApplication {

  /** 退役命令必须在启动 Spring 或访问数据库之前明确拒绝。 */
  private static final String DATABASE_UPGRADE = "database-upgrade";

  public static void main(String[] args) {
    if (args.length > 0 && DATABASE_UPGRADE.equals(args[0])) {
      throw new IllegalArgumentException(
          "database-upgrade is unsupported in 2.0; back up/export configuration and rebuild a new database.");
    }
    SpringApplication.run(WebApplication.class, args);
  }
}
