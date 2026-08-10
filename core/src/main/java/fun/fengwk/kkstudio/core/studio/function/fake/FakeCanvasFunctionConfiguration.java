package fun.fengwk.kkstudio.core.studio.function.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;

/** fake models 仅在 S3 与显式 fake flag 同时开启时进入 registry。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kk-studio.storage.s3", name = "enabled", havingValue = "true")
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
