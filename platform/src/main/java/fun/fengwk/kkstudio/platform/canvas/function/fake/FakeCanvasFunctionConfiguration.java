package fun.fengwk.kkstudio.platform.canvas.function.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;

/** fake models 在显式 fake flag 开启时进入 registry。 */
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
