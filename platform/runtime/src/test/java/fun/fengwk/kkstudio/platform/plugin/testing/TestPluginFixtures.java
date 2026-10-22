package fun.fengwk.kkstudio.platform.plugin.testing;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 宿主 Plugin 测试的忠实最小夹具：无普通配置、无秘密、两域贡献均为空的实例。 */
public final class TestPluginFixtures {

  /** 无普通值、无默认值、无秘密的空配置 schema。 */
  public static final PluginConfigSchema EMPTY_SCHEMA =
      new PluginConfigSchema(new InputSchema("config", Map.of(), Set.of(), false), "{}", Map.of());

  /** 无普通值、无默认值、但声明若干秘密字段的配置 schema。 */
  public static PluginConfigSchema emptySchemaWithSecrets(Map<String, String> secrets) {
    return new PluginConfigSchema(
        new InputSchema("config", Map.of(), Set.of(), false), "{}", secrets);
  }

  private TestPluginFixtures() {}

  /** 两域贡献均为空的实例；close 无资源可释放。 */
  public static PluginInstance emptyInstance() {
    return new PluginInstance() {
      @Override
      public List<HarnessContributor> harnessContributors() {
        return List.of();
      }

      @Override
      public List<CanvasFunctionAdapter> canvasAdapters() {
        return List.of();
      }

      @Override
      public void close() {}
    };
  }
}
