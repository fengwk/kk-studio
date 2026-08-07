package fun.fengwk.kkstudio.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Core 模块的 Spring Boot 测试入口。
 *
 * <p>Core 不是 Harness 的组合根：生产 {@code EnvironmentReadyListener} 由 web 模块提供，因此测试上下文通过 {@link
 * CoreHarnessTestConfiguration} 导入，使环境网关能用 no-op READY 桥接启动。
 */
@SpringBootApplication
@Import(CoreHarnessTestConfiguration.class)
public class CoreTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreTestApplication.class, args);
  }
}
