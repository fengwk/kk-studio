package fun.fengwk.kkstudio.plugin.opencli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** {@link OpenCliAutoConfiguration} 自动装配单一 Bean 契约与 imports 声明测试。 */
class OpenCliAutoConfigurationTest {

  private static final String IMPORTS_PATH =
      "META-INF/spring/" + "org.springframework.boot.autoconfigure." + "AutoConfiguration.imports";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OpenCliAutoConfiguration.class));

  @Test
  void autoConfigurationRegistersOnlyOneStudioPluginBean() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();

          // 只注册一个 StudioPlugin bean
          assertThat(context).hasSingleBean(StudioPlugin.class);
          assertThat(context).hasSingleBean(OpenCliPlugin.class);

          StudioPlugin plugin = context.getBean(StudioPlugin.class);
          assertEquals(OpenCliPlugin.PLUGIN_ID, plugin.descriptor().pluginId());

          // 不发布 client、adapter 或 contributor 为宿主内置 Bean
          assertThat(context).doesNotHaveBean(OpenCliHubClient.class);
          assertThat(context).doesNotHaveBean(GptImage2CanvasFunctionAdapter.class);
          assertThat(context).doesNotHaveBean(SeedanceCanvasFunctionAdapter.class);
          assertThat(context.getBeansOfType(CanvasFunctionAdapter.class)).isEmpty();
          assertThat(context.getBeansOfType(HarnessContributor.class)).isEmpty();
        });
  }

  @Test
  void customOpenCliPluginOverridesDefault() {
    OpenCliPlugin customPlugin = mock(OpenCliPlugin.class);

    runner
        .withBean(OpenCliPlugin.class, () -> customPlugin)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(StudioPlugin.class);
              assertEquals(customPlugin, context.getBean(StudioPlugin.class));
              assertThat(context).hasSingleBean(OpenCliPlugin.class);
            });
  }

  @Test
  void unrelatedPluginDoesNotSuppressOpenCliInstallation() {
    runner
        .withBean("anotherPlugin", StudioPlugin.class, () -> mock(StudioPlugin.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(StudioPlugin.class)).hasSize(2);
              assertThat(context).hasSingleBean(OpenCliPlugin.class);
            });
  }

  @Test
  void wiresCustomObjectMapperWhenPresent() {
    ObjectMapper customMapper = JsonMapper.builder().build();

    runner
        .withBean(ObjectMapper.class, () -> customMapper)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(StudioPlugin.class);
              assertThat(context).hasSingleBean(OpenCliPlugin.class);
            });
  }

  @Test
  void importsFileDeclaresAutoConfigurationExactly() throws IOException {
    ClassLoader classLoader = getClass().getClassLoader();
    try (InputStream stream = classLoader.getResourceAsStream(IMPORTS_PATH)) {
      assertThat(stream)
          .withFailMessage(
              "AutoConfiguration.imports file must exist on classpath: " + IMPORTS_PATH)
          .isNotNull();

      String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      List<String> declarations =
          Arrays.stream(content.split("\\R"))
              .map(String::trim)
              .filter(line -> !line.isBlank() && !line.startsWith("#"))
              .toList();

      assertEquals(
          List.of(OpenCliAutoConfiguration.class.getName()),
          declarations,
          "AutoConfiguration.imports must declare OpenCliAutoConfiguration exactly");
    }
  }
}
