package fun.fengwk.kkstudio.plugin.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;
import java.util.Set;

/**
 * MediaPlugin 安装定义与实例装配单元测试。
 *
 * <p>覆盖安装身份、默认启用、无认证/无秘密、坏配置拒绝、启用/停用两域贡献、贡献不可变与 close 行为。凭据上下文一律使用 Mockito mock：本插件绝不访问宿主 context。
 */
class MediaPluginTest {

  private static final PluginContext CONTEXT = mock(PluginContext.class);

  private final MediaPlugin plugin = new MediaPlugin();

  /** 安装身份、无认证声明与可选的认证能力全部缺席。 */
  @Test
  void declaresInstallIdentityAndNoAuthentication() {
    assertEquals("media", MediaPlugin.DESCRIPTOR.pluginId());
    assertEquals("Media", MediaPlugin.DESCRIPTOR.name());
    assertEquals("1.0.0", MediaPlugin.DESCRIPTOR.version());
    assertSame(MediaPlugin.DESCRIPTOR, plugin.descriptor());
    assertEquals(new PluginAuthSpec.None(), plugin.authenticationSpec());
    assertTrue(plugin.authHandler().isEmpty(), "无认证能力");
    assertTrue(plugin.refresher().isEmpty(), "无凭据刷新能力");
    assertSame(MediaPlugin.CONFIGURATION_SCHEMA, plugin.configurationSchema());
  }

  /** 唯一配置字段是 enabled:boolean，默认启用，且没有秘密字段。 */
  @Test
  void schemaDeclaresOnlyEnabledAndDefaultsToTrue() {
    PluginConfigSchema schema = plugin.configurationSchema();
    assertTrue(schema.secrets().isEmpty(), "本插件不声明任何秘密字段");
    assertEquals("{\"enabled\":true}", schema.defaultsJson());
    assertEquals(Set.of("enabled"), schema.valuesSchema().properties().keySet());
    assertFalse(schema.valuesSchema().additionalProperties(), "配置必须拒绝未知字段");
    assertEquals("{\"enabled\":true}", schema.resolveValues("{}"), "缺失字段由默认值补齐为启用");
  }

  /** 严格 schema 拒绝未知字段与错误类型。 */
  @Test
  void schemaRejectsUnknownFieldsAndWrongTypes() {
    PluginConfigSchema schema = plugin.configurationSchema();
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{\"unknown\":true}"));
    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"enabled\":\"true\"}"));
  }

  /** 默认配置（revision=0、schema 默认值）即产出启用实例。 */
  @Test
  void defaultConfigurationProducesEnabledInstance() {
    PluginInstance instance =
        plugin.configure(PluginConfig.initial(plugin.configurationSchema()), CONTEXT);
    assertEquals(1, instance.canvasAdapters().size(), "默认必须启用并贡献函数");
    instance.close();
  }

  /** 启用时贡献一个 IMAGE 裁剪 adapter，Harness 贡献为空。 */
  @Test
  void enabledConfigurationContributesOneImageCropAdapter() {
    PluginInstance instance = plugin.configure(new PluginConfig(0, "{\"enabled\":true}"), CONTEXT);

    assertTrue(instance.harnessContributors().isEmpty());
    List<CanvasFunctionAdapter> adapters = instance.canvasAdapters();
    assertEquals(1, adapters.size());
    CanvasFunctionAdapter adapter = adapters.get(0);
    assertTrue(adapter instanceof MediaFunctionAdapter);
    assertEquals(
        List.of("image.crop"),
        adapter.functions().stream().map(CanvasFunctionDefinition::name).toList());
    assertTrue(adapter.enabled());
    assertNull(adapter.unavailableReason());

    verifyNoInteractions(CONTEXT);
    instance.close();
  }

  /** 停用时两域贡献都为空。 */
  @Test
  void disabledConfigurationContributesNothing() {
    PluginInstance instance = plugin.configure(new PluginConfig(0, "{\"enabled\":false}"), CONTEXT);

    assertTrue(instance.canvasAdapters().isEmpty());
    assertTrue(instance.harnessContributors().isEmpty());
    verifyNoInteractions(CONTEXT);
    instance.close();
  }

  /** 冻结的贡献列表不可变，close 没有资源可释放且可重复调用。 */
  @Test
  void contributionsAreImmutableAndCloseIsIdempotent() {
    PluginInstance instance = plugin.configure(new PluginConfig(0, "{\"enabled\":true}"), CONTEXT);

    assertThrows(UnsupportedOperationException.class, () -> instance.canvasAdapters().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> instance.harnessContributors().add(null));

    instance.close();
    instance.close();
  }

  /** configure 拒绝 null 入参与不符合 schema 的坏配置。 */
  @Test
  void configureRejectsNullArgumentsAndBadValues() {
    assertThrows(NullPointerException.class, () -> plugin.configure(null, CONTEXT));
    assertThrows(
        NullPointerException.class, () -> plugin.configure(new PluginConfig(0, "{}"), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> plugin.configure(new PluginConfig(0, "{\"enabled\":1}"), CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> plugin.configure(new PluginConfig(0, "{\"enabled\":true,\"extra\":1}"), CONTEXT));
  }
}
