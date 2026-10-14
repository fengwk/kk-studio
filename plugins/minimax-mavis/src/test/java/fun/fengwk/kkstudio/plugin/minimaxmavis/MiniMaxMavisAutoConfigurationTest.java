package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

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
 * <p>验证通过 ApplicationContextRunner 加载 MiniMaxMavisAutoConfiguration 时只装配安装定义及其无状态认证依赖，且启动不触发任何网络调用；
 * 验证 15 个工具、capability 缓存与工具执行器都不是容器 bean，而由 {@code configure} 按配置装配为实例资源；验证不加载
 * MiniMaxMavisAutoConfiguration 时容器无残留 Plugin 语义与工具；验证 AutoConfiguration.imports
 * 文件精确声明了该配置类（加回即出现、去掉即消失）。
 */
class MiniMaxMavisAutoConfigurationTest {

  private static final String IMPORTS_PATH =
      "META-INF/spring/" + "org.springframework.boot.autoconfigure." + "AutoConfiguration.imports";

  private final ApplicationContextRunner baseRunner = new ApplicationContextRunner();

  /** 自动配置只出现安装定义与无状态认证 bean；工具/缓存/执行器由实例私有，且启动完全不触碰网络传输。 */
  @Test
  void autoConfigurationBindsOnlyTheDefinitionWithoutTransportCalls() {
    RecordingTransport recordingTransport = new RecordingTransport();

    baseRunner
        .withBean(MavisHttpTransport.class, () -> recordingTransport)
        .withConfiguration(AutoConfigurations.of(MiniMaxMavisAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              assertThat(context).hasSingleBean(StudioPlugin.class);
              assertThat(context).hasSingleBean(MiniMaxMavisPlugin.class);

              // 旧装配路径必须彻底消失：没有独立 Contributor、工具执行器、capability 缓存或资源访问 bean。
              assertThat(context).doesNotHaveBean(MiniMaxMavisHarnessContributor.class);
              assertThat(context).doesNotHaveBean(MiniMaxMavisCapabilityCache.class);
              assertThat(context).doesNotHaveBean(MiniMaxMavisResourceAccess.class);
              assertThat(context.containsBean("mavisToolExecutor")).isFalse();
              assertThat(context.getBeansOfType(HarnessContributor.class)).isEmpty();
              assertThat(context.getBeansOfType(Tool.class)).isEmpty();

              // 唯一注册点仍是安装定义：默认配置由它装配出完整 15 工具的实例。
              StudioPlugin plugin = context.getBean(StudioPlugin.class);
              assertEquals(MiniMaxMavisPlugin.PLUGIN_ID, plugin.descriptor().pluginId());

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
          assertThat(context).doesNotHaveBean(PluginInstance.class);
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
