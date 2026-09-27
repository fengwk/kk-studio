package fun.fengwk.kkstudio.plugin.canvasmedia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * CanvasMediaAutoConfiguration 自动装配与契约凭证测试。
 *
 * <p>验证通过 ApplicationContextRunner 加载 CanvasMediaAutoConfiguration 时正确注册
 * CanvasMediaFunctionAdapter，并支持用户自定义 bean 覆盖；验证 AutoConfiguration.imports 声明符合 Spring Boot 约定。
 */
class CanvasMediaAutoConfigurationTest {

  private static final String IMPORTS_PATH =
      "META-INF/spring/" + "org.springframework.boot.autoconfigure." + "AutoConfiguration.imports";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CanvasMediaAutoConfiguration.class));

  /** 验证自动配置加载时注册单个 CanvasMediaFunctionAdapter 并且作为 CanvasFunctionAdapter 可见。 */
  @Test
  void autoConfigurationRegistersCanvasMediaFunctionAdapter() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(CanvasMediaFunctionAdapter.class);
          assertThat(context).hasSingleBean(CanvasFunctionAdapter.class);
        });
  }

  /** 验证当用户显式定义 CanvasMediaFunctionAdapter 时，自动配置退让。 */
  @Test
  void customBeanOverridesDefaultAdapter() {
    CanvasMediaFunctionAdapter customAdapter = new CanvasMediaFunctionAdapter();
    runner
        .withBean(CanvasMediaFunctionAdapter.class, () -> customAdapter)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CanvasMediaFunctionAdapter.class);
              assertEquals(customAdapter, context.getBean(CanvasMediaFunctionAdapter.class));
            });
  }

  /** 验证 AutoConfiguration.imports 文件声明了 CanvasMediaAutoConfiguration。 */
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
      assertTrue(
          entries.contains(CanvasMediaAutoConfiguration.class.getName()),
          "AutoConfiguration.imports 必须包含 CanvasMediaAutoConfiguration");
    }
  }
}
