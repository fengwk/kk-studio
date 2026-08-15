package fun.fengwk.kkstudio.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * web 模块的 Spring Boot 测试入口。
 *
 * <p>基于 Postgres 的集成套件通过 PostgreSQL 序列锁定持久化 id；基于 Redis 的实时适配器在执行测试时仍使用 Testcontainers。
 */
@SpringBootApplication
public class WebTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebTestApplication.class, args);
  }
}
