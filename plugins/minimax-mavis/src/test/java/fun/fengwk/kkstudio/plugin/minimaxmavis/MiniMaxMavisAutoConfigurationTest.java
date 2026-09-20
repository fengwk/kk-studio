package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * MiniMax Mavis Plugin 自动配置边界与架构证据测试。
 *
 * <p>验证通过 ApplicationContextRunner 加载 MiniMaxMavisAutoConfiguration 时完整装配
 * StudioPlugin、MiniMaxMavisHarnessContributor、HarnessContributor 与全部 15 个工具，且启动不触发任何网络调用； 验证不加载
 * MiniMaxMavisAutoConfiguration 时容器无残留 Plugin 语义与工具； 验证 AutoConfiguration.imports
 * 文件精确声明了该配置类（加回即出现、去掉即消失）。
 */
class MiniMaxMavisAutoConfigurationTest {

  private static final String IMPORTS_PATH =
      "META-INF/spring/" + "org.springframework.boot.autoconfigure." + "AutoConfiguration.imports";

  private final ApplicationContextRunner baseRunner =
      new ApplicationContextRunner()
          .withBean(PluginCredentialStore.class, () -> mock(PluginCredentialStore.class))
          .withBean(PluginResourceGateway.class, () -> mock(PluginResourceGateway.class));

  /** 验证加载自动配置时，正确注入 StudioPlugin、HarnessContributor 与 15 项工具，且启动过程完全不触碰网络传输。 */
  @Test
  void autoConfigurationBindsPluginAndToolsWithoutTransportCalls() {
    RecordingTransport recordingTransport = new RecordingTransport();

    baseRunner
        .withBean(MavisHttpTransport.class, () -> recordingTransport)
        .withConfiguration(AutoConfigurations.of(MiniMaxMavisAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              assertThat(context).hasSingleBean(StudioPlugin.class);
              assertThat(context).hasSingleBean(MiniMaxMavisPlugin.class);
              assertThat(context).hasSingleBean(HarnessContributor.class);
              assertThat(context).hasSingleBean(MiniMaxMavisHarnessContributor.class);

              MiniMaxMavisHarnessContributor contributor =
                  context.getBean(MiniMaxMavisHarnessContributor.class);
              assertEquals(
                  15, contributor.toolCount(), "Contributor must provide exactly 15 tools");

              for (MavisCapability capability : MavisCapability.values()) {
                Tool tool = contributor.tools().get(capability);
                assertThat(tool).isNotNull();
                assertEquals(capability.toolName(), tool.descriptor().name());
              }

              // 启动期断言：绝不触发任何 HTTP 传输调用（未联网启动）
              assertTrue(
                  recordingTransport.requests().isEmpty(),
                  "ApplicationContext startup must not trigger any MavisHttpTransport calls");
            });
  }

  /**
   * 验证当没有加载 MiniMaxMavisAutoConfiguration 时（模拟 classpath 上没有该插件）， 容器中绝不包含
   * StudioPlugin、HarnessContributor 或任何 MiniMax 工具。
   */
  @Test
  void withoutAutoConfigurationContextContainsNoPluginOrTools() {
    baseRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(StudioPlugin.class);
          assertThat(context).doesNotHaveBean(MiniMaxMavisPlugin.class);
          assertThat(context).doesNotHaveBean(HarnessContributor.class);
          assertThat(context).doesNotHaveBean(MiniMaxMavisHarnessContributor.class);
          assertThat(context.getBeansOfType(Tool.class)).isEmpty();
        });
  }

  /**
   * 验证 AutoConfiguration.imports 文件精确声明了 MiniMaxMavisAutoConfiguration， 作为 Spring Boot 3/4
   * 架构约定的自动装配契约凭证。
   */
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
          List.of(MiniMaxMavisAutoConfiguration.class.getName()),
          declarations,
          "AutoConfiguration.imports must declare MiniMaxMavisAutoConfiguration exactly");
    }
  }

  /** 记录 HTTP 请求的假传输实现。 */
  private static final class RecordingTransport implements MavisHttpTransport {
    private final List<MavisHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requests.add(request);
      return new MavisHttpResponse(200, "{\"tools\":[]}");
    }

    public List<MavisHttpRequest> requests() {
      return requests;
    }
  }
}
