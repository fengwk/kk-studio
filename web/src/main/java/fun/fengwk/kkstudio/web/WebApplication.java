package fun.fengwk.kkstudio.web;

import fun.fengwk.convention4j.springboot.starter.json.JacksonAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import fun.fengwk.kkstudio.web.upgrade.LegacyDatabaseUpgradeCli;

import java.util.Arrays;

/**
 * @author fengwk
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class WebApplication {

  /** 离线升级子命令；作为唯一 Fat JAR 的首个参数时走离线入口，不启动 Spring Web/Worker。 */
  private static final String DATABASE_UPGRADE = "database-upgrade";

  public static void main(String[] args) {
    if (args.length > 0 && DATABASE_UPGRADE.equals(args[0])) {
      System.exit(
          LegacyDatabaseUpgradeCli.run(
              Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
    }
    SpringApplication.run(WebApplication.class, args);
  }
}
