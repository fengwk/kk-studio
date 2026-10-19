package fun.fengwk.kkstudio.platform.canvas.function.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;

/**
 * 仅 deploy/test 与 E2E 栈通过 {@code KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED=true} 显式开启；它需要随真实后端镜像运行，
 * 因此留在 main，默认不注册，生产部署不得设置该开关。
 */
@Configuration(proxyBeanMethods = false)
public class FakeCanvasFunctionConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.canvas.function",
      name = "fake-enabled",
      havingValue = "true")
  public CanvasFunctionAdapter fakeCanvasFunctionAdapter() {
    return new FakeCanvasFunctionAdapter();
  }
}
