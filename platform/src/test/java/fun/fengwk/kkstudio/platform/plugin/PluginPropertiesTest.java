package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

/**
 * 部署配置的 wire 名称契约。
 *
 * <p>运维文档只承诺 {@code KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE} 等环境变量名，因此这里同时锁定环境变量形态（Spring relaxed
 * binding）与 kebab-case 属性形态：任一边被重命名都会让生产配置静默失效，属于必须被测试守住的部署边界。
 */
class PluginPropertiesTest {

  private static Binder bindWithEnvironmentVariable(String name, String value) {
    ConfigurableEnvironment environment = new StandardEnvironment();
    // 环境变量属性源必须优先，模拟真实部署：容器只注入 KK_STUDIO_* 变量。
    environment
        .getPropertySources()
        .addFirst(new SystemEnvironmentPropertySource("env", Map.of(name, value)));
    // Spring Boot 启动时会把 environment 包装为 canonical 视图，relaxed binding 依赖这一步。
    ConfigurationPropertySources.attach(environment);
    return Binder.get(environment);
  }

  private static Binder bindWithProperty(String name, String value) {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(name, value)));
    return Binder.get(environment);
  }

  /** 环境变量 `KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE` 必须绑定到 credentialKeyFile。 */
  @Test
  void bindsCredentialKeyFileFromEnvironmentVariable() {
    PluginProperties properties =
        bindWithEnvironmentVariable(
                "KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE", "/run/secrets/plugin-credential.key")
            .bind("kk-studio.plugins", Bindable.of(PluginProperties.class))
            .get();

    assertEquals("/run/secrets/plugin-credential.key", properties.getCredentialKeyFile());
  }

  /**
   * 刷新节奏的部署入口是 {@code application.yml} 中的显式占位符（{@code ${KK_STUDIO_PLUGINS_REFRESH_POLL_DELAY:1h}}），
   * 因此这里锁定的是占位符解析后的 canonical 属性绑定。
   *
   * <p>注意不要把它误写成「环境变量直接绑定嵌套字段」：Spring 的 env 映射把下划线视为层级分隔， {@code
   * KK_STUDIO_PLUGINS_REFRESH_POLL_DELAY} 不会直接命中 {@code refresh.poll-delay}，这正是 {@code
   * application.yml} 必须显式声明占位符（而不是依赖 relaxed binding）的原因。
   */
  @Test
  void bindsRefreshScheduleFromCanonicalProperties() {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "kk-studio.plugins.refresh.poll-delay", "30s",
                    "kk-studio.plugins.refresh.lease-duration", "45s")));

    PluginProperties properties =
        Binder.get(environment)
            .bind("kk-studio.plugins", Bindable.of(PluginProperties.class))
            .get();

    assertEquals(Duration.ofSeconds(30), properties.getRefresh().getPollDelay());
    assertEquals(Duration.ofSeconds(45), properties.getRefresh().getLeaseDuration());
  }

  /** 未配置时主密钥为空（本部署不提供凭据能力），刷新节奏回落到文档承诺的 1h / 2m。 */
  @Test
  void defaultsKeepCredentialCapabilityOffAndDocumentedRefreshCadence() {
    PluginProperties properties =
        bindWithProperty("unused", "value")
            .bind("kk-studio.plugins", Bindable.of(PluginProperties.class))
            .orElseGet(PluginProperties::new);

    assertTrue(
        properties.getCredentialKeyFile() == null || properties.getCredentialKeyFile().isEmpty());
    assertEquals(Duration.ofHours(1), properties.getRefresh().getPollDelay());
    assertEquals(Duration.ofMinutes(2), properties.getRefresh().getLeaseDuration());
  }

  /** 非正的刷新节奏必须启动期失败，避免配置错误退化成忙轮询或永不失效的 lease。 */
  @Test
  void rejectsNonPositiveRefreshDurations() {
    PluginProperties properties = new PluginProperties();

    assertThrows(
        IllegalArgumentException.class, () -> properties.getRefresh().setPollDelay(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.getRefresh().setPollDelay(Duration.ofSeconds(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.getRefresh().setLeaseDuration(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> properties.getRefresh().setLeaseDuration(null));
  }
}
