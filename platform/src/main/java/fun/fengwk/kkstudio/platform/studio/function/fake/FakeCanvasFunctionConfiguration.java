package fun.fengwk.kkstudio.platform.studio.function.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;

import java.util.Objects;

/** fake models 在显式 fake flag 与 SystemSettings.storageMedia.s3Enabled 同时开启时进入 registry。 */
@Configuration(proxyBeanMethods = false)
public class FakeCanvasFunctionConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.canvas.function",
      name = "fake-enabled",
      havingValue = "true")
  public CanvasFunctionAdapter fakeCanvasFunctionAdapter(SystemSettingsSnapshot snapshot) {
    if (!Objects.requireNonNull(snapshot, "snapshot").get().storageMedia().s3Enabled()) {
      return null;
    }
    return new FakeCanvasFunctionAdapter();
  }
}
