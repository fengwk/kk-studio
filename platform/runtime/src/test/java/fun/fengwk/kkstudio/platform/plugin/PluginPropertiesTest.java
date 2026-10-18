package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.util.Map;

/**
 * 部署配置的 wire 名称契约。
 *
 * <p>kebab-case 属性名是 {@code application.yml} 占位符的绑定目标：重命名会让生产配置静默失效，属于必须被测试守住的部署边界。
 */
class PluginPropertiesTest {

  private static Binder bindWithProperty(String name, String value) {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(name, value)));
    return Binder.get(environment);
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

  /** 未配置时刷新节奏回落到文档承诺的 1h / 2m。 */
  @Test
  void defaultsToTheDocumentedRefreshCadence() {
    PluginProperties properties =
        bindWithProperty("unused", "value")
            .bind("kk-studio.plugins", Bindable.of(PluginProperties.class))
            .orElseGet(PluginProperties::new);

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
