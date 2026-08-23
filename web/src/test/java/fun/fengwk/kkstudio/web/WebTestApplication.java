package fun.fengwk.kkstudio.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * web 模块的 Spring Boot 测试入口。
 *
 * <p>集成套件通过 PostgreSQL 序列锁定持久化 id，并通过 notification adapter 验证 live overlay。
 */
@SpringBootApplication
public class WebTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebTestApplication.class, args);
  }
}
