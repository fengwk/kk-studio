package fun.fengwk.kkstudio.plugin.media;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Media 的 Studio Plugin 安装期定义：安装身份、受控配置与运行期实例装配。
 *
 * <p>本插件不需要认证也不消费凭据：认证声明是 {@link PluginAuthSpec.None}，schema 没有秘密字段，配置与执行都不解析凭据、不联网。 启动期静态冻结
 * descriptor 与受控配置 schema，运行期实例由 {@link #configure(PluginConfig, PluginContext)} 装配并归本 Plugin 所有。
 */
public final class MediaPlugin implements StudioPlugin {

  public static final String PLUGIN_ID = "media";
  public static final String PLUGIN_NAME = "Media";
  public static final String PLUGIN_VERSION = "1.0.0";

  public static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(PLUGIN_ID, PLUGIN_NAME, PLUGIN_VERSION);

  private static final PluginAuthSpec AUTHENTICATION_SPEC = new PluginAuthSpec.None();

  /** 唯一开关默认启用：安装即贡献基础媒体函数，显式停用时两域贡献为空。 */
  private static final String DEFAULTS_JSON = "{\"enabled\":true}";

  private static final InputSchema VALUES_SCHEMA = createValuesSchema();

  public static final PluginConfigSchema CONFIGURATION_SCHEMA =
      new PluginConfigSchema(VALUES_SCHEMA, DEFAULTS_JSON, Map.of());

  private static InputSchema createValuesSchema() {
    return new InputSchema(
        "Media configuration",
        Map.of("enabled", new BooleanSchema("whether the media plugin contributes its functions")),
        Set.of("enabled"),
        false);
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public PluginConfigSchema configurationSchema() {
    return CONFIGURATION_SCHEMA;
  }

  @Override
  public PluginAuthSpec authenticationSpec() {
    return AUTHENTICATION_SPEC;
  }

  @Override
  public PluginInstance configure(PluginConfig config, PluginContext context) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(context, "context");
    String resolved = CONFIGURATION_SCHEMA.resolveValues(config.valuesJson());
    JsonNode enabled = JsonValues.readTree(resolved).path("enabled");
    return new MediaInstance(enabled.asBoolean());
  }
}
