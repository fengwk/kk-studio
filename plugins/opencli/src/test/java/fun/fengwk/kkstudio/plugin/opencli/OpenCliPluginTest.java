package fun.fengwk.kkstudio.plugin.opencli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.Optional;
import java.util.Set;

/** {@link OpenCliPlugin} 安装期定义、受控配置 Schema、静态认证契约与零出网配置装配测试。 */
class OpenCliPluginTest {

  private final OpenCliPlugin plugin = new OpenCliPlugin();

  @Test
  void descriptorMatchesContract() {
    PluginDescriptor descriptor = plugin.descriptor();
    assertEquals("opencli", descriptor.pluginId());
    assertEquals("OpenCLI", descriptor.name());
    assertEquals("1.0.0", descriptor.version());
  }

  @Test
  void authenticationSpecMatchesContract() {
    PluginAuthSpec authSpec = plugin.authenticationSpec();
    assertThat(authSpec).isInstanceOf(PluginAuthSpec.StaticKey.class);

    PluginAuthSpec.StaticKey staticKey = (PluginAuthSpec.StaticKey) authSpec;
    assertEquals("apiKey", staticKey.secretField());
    assertEquals(Set.of("baseUrl"), staticKey.contextFields());

    assertTrue(plugin.authHandler().isEmpty());
    assertTrue(plugin.refresher().isEmpty());
  }

  @Test
  void configurationSchemaMatchesContract() {
    PluginConfigSchema schema = plugin.configurationSchema();
    assertNotNull(schema);
    assertEquals(Set.of("apiKey", "instanceId"), schema.secrets().keySet());
    schema.requireDeclaredSecrets(plugin.authenticationSpec());

    // 空对象补齐默认值：默认值的唯一来源就是 schema 本身
    String resolved = schema.resolveValues("{}");
    OpenCliSettings settings = OpenCliSettings.parse(resolved);
    assertEquals(OpenCliTestSettings.defaults(), settings);

    // 私有身份不是普通配置字段：无论是否已录入秘密，都不能作为 values 出现
    assertThatThrownBy(() -> schema.resolveValues("{\"instanceId\": \"prod-1\"}"))
        .isInstanceOf(IllegalArgumentException.class);

    // 未知字段拒绝
    assertThatThrownBy(() -> schema.resolveValues("{\"unknownProperty\": true}"))
        .isInstanceOf(IllegalArgumentException.class);

    // 显式 null 传入非 nullable 字段拒绝
    assertThatThrownBy(() -> schema.resolveValues("{\"enabled\": null}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configureBuildsInstanceWithoutNetworkOrCredentialResolution() {
    PluginCredentialAccess credentialAccess = mock(PluginCredentialAccess.class);
    PluginContext context = new PluginContext(credentialAccess, Optional.empty());

    PluginConfig config = PluginConfig.initial(plugin.configurationSchema());
    PluginInstance instance = plugin.configure(config, context);

    assertNotNull(instance);
    assertTrue(instance.harnessContributors().isEmpty());
    assertEquals(2, instance.canvasAdapters().size());

    // 严密断言：configure 过程绝对不读取或解析凭据（零出网、零解析）
    verifyNoInteractions(credentialAccess);

    instance.close();
  }

  @Test
  void configureValidationErrors() {
    PluginCredentialAccess credentialAccess = mock(PluginCredentialAccess.class);
    PluginContext context = new PluginContext(credentialAccess, Optional.empty());
    PluginConfig validConfig = PluginConfig.initial(plugin.configurationSchema());

    assertThatThrownBy(() -> plugin.configure(null, context))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("config");

    assertThatThrownBy(() -> plugin.configure(validConfig, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("context");

    // 开启但未提供 baseUrl 校验失败
    PluginConfig invalidConfig = new PluginConfig(1L, "{\"enabled\": true}");
    assertThatThrownBy(() -> plugin.configure(invalidConfig, context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed configuration payload");
  }

  @Test
  void customObjectMapperConstructor() {
    ObjectMapper mapper = JsonMapper.builder().build();
    OpenCliPlugin customPlugin = new OpenCliPlugin(mapper);
    assertEquals("opencli", customPlugin.descriptor().pluginId());
  }
}
