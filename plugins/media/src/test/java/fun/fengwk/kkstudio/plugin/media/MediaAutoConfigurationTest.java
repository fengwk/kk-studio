package fun.fengwk.kkstudio.plugin.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * MediaAutoConfiguration 自动装配与契约凭证测试。
 *
 * <p>验证加载 MediaAutoConfiguration 时只注册一个 {@link StudioPlugin} 安装定义，不再把函数 adapter 发布为宿主内置 Bean；
 * 验证用户自定义 Bean 覆盖，以及 AutoConfiguration.imports 声明符合 Spring Boot 约定。
 */
class MediaAutoConfigurationTest {

  private static final String IMPORTS_PATH =
      "META-INF/spring/" + "org.springframework.boot.autoconfigure." + "AutoConfiguration.imports";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MediaAutoConfiguration.class));

  /** 装配唯一 StudioPlugin 安装定义，且容器中不存在内置 CanvasFunctionAdapter。 */
  @Test
  void autoConfigurationRegistersOnlyTheStudioPlugin() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(MediaPlugin.class);
          assertThat(context).hasSingleBean(StudioPlugin.class);
          assertThat(context.getBeansOfType(CanvasFunctionAdapter.class)).isEmpty();
        });
  }

  /** 用户显式定义 MediaPlugin 时，自动配置退让。 */
  @Test
  void customStudioPluginOverridesDefault() {
    MediaPlugin custom = new MediaPlugin();
    runner
        .withBean(MediaPlugin.class, () -> custom)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(StudioPlugin.class);
              assertThat(context.getBean(StudioPlugin.class)).isSameAs(custom);
            });
  }

  /** AutoConfiguration.imports 只声明 MediaAutoConfiguration。 */
  @Test
  void importsFileDeclaresAutoConfiguration() throws IOException {
    ClassLoader classLoader = getClass().getClassLoader();
    try (InputStream inputStream = classLoader.getResourceAsStream(IMPORTS_PATH)) {
      assertThat(inputStream).isNotNull();
      String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
      List<String> entries =
          Arrays.stream(content.split("\\R"))
              .map(String::strip)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .toList();
      assertThat(entries).containsExactly(MediaAutoConfiguration.class.getName());
    }
  }
}
