package fun.fengwk.kkstudio.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Platform 模块的 Spring Boot 测试入口。
 *
 * <p>Platform 不是 Harness 的组合根：生产 {@code EnvironmentReadyListener} 由 web 模块提供，因此测试上下文通过 {@link
 * PlatformHarnessTestConfiguration} 导入，使环境网关能用 no-op READY 桥接启动。
 */
@SpringBootApplication
@Import(PlatformHarnessTestConfiguration.class)
public class PlatformTestApplication {

  public static void main(String[] args) {
    SpringApplication.run(PlatformTestApplication.class, args);
  }
}
