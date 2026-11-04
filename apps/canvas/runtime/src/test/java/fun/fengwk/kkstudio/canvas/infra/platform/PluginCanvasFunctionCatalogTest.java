package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogSnapshot.ConfiguredPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link PluginCanvasFunctionCatalog} 的冻结契约：插件与内置 adapter 一次性合并校验，函数名按 adapter 身份归属插件，读取不触碰 adapter
 * 定义以外的状态。
 */
class PluginCanvasFunctionCatalogTest {

  /** 插件与内置 adapter 合并成唯一目录，归属只记录插件贡献。 */
  @Test
  void mergesPluginAndBuiltinAdaptersAndAttributesNamesToPlugins() {
    CanvasFunctionAdapter pluginAdapter = adapter(definition("plugin.render"));
    CanvasFunctionAdapter builtinAdapter = adapter(definition("builtin.render"));
    PluginCatalogSnapshot snapshot = snapshot(plugin("alpha", pluginAdapter));

    PluginCanvasFunctionCatalog view =
        PluginCanvasFunctionCatalog.from(snapshot, List.of(builtinAdapter));

    assertEquals(
        List.of("builtin.render", "plugin.render"),
        view.catalog().list().stream().map(registered -> registered.function().name()).toList());
    assertEquals(List.of("plugin.render"), view.functionNames("alpha"));
    assertEquals(List.of(), view.functionNames("unknown"));
    // 内置 adapter 进入目录但不归属任何插件。
    assertSame(builtinAdapter, view.catalog().require("builtin.render").adapter());
  }

  /** 插件内的函数名按目录顺序（字典序）冻结，与 adapter 声明顺序无关。 */
  @Test
  void freezesFunctionNamesInCatalogOrder() {
    CanvasFunctionAdapter adapter = adapter(definition("zeta.run"), definition("alpha.run"));
    PluginCatalogSnapshot snapshot = snapshot(plugin("alpha", adapter));

    PluginCanvasFunctionCatalog view = PluginCanvasFunctionCatalog.from(snapshot, List.of());

    assertEquals(List.of("alpha.run", "zeta.run"), view.functionNames("alpha"));
  }

  /** 插件与内置 adapter 声明同名函数时整份目录被拒绝，绝不静默取舍。 */
  @Test
  void rejectsDuplicateFunctionNamesAcrossPluginAndBuiltin() {
    PluginCatalogSnapshot snapshot = snapshot(plugin("alpha", adapter(definition("shared.run"))));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            PluginCanvasFunctionCatalog.from(snapshot, List.of(adapter(definition("shared.run")))));
  }

  /** 未声明任何函数的 adapter 让整份目录被拒绝。 */
  @Test
  void rejectsAdaptersWithoutFunctions() {
    PluginCatalogSnapshot snapshot = snapshot(plugin("alpha", adapter()));

    assertThrows(
        IllegalArgumentException.class,
        () -> PluginCanvasFunctionCatalog.from(snapshot, List.of()));
  }

  /** 管理面投影与目录读取使用同一份已冻结视图。 */
  @Test
  void readsTheSameFrozenViewFromTheSnapshot() {
    PluginCatalogSnapshot snapshot = snapshot(plugin("alpha", adapter(definition("alpha.run"))));
    PluginCanvasFunctionCatalog view = PluginCanvasFunctionCatalog.from(snapshot, List.of());
    when(snapshot.productCatalog(PluginCanvasFunctionCatalog.class)).thenReturn(view);

    assertSame(view, PluginCanvasFunctionCatalog.of(snapshot));
    assertEquals(
        List.of("alpha.run"),
        PluginCanvasFunctionCatalog.functionNames(snapshot, snapshot.configuredPlugins().get(0)));
  }

  private static PluginCatalogSnapshot snapshot(ConfiguredPlugin... plugins) {
    PluginCatalogSnapshot snapshot = mock(PluginCatalogSnapshot.class);
    when(snapshot.configuredPlugins()).thenReturn(List.of(plugins));
    return snapshot;
  }

  private static ConfiguredPlugin plugin(String pluginId, CanvasFunctionAdapter adapter) {
    return new ConfiguredPlugin(
        new PluginDescriptor(pluginId, "Plugin " + pluginId, "1.0"),
        new PluginConfig(0, "{}"),
        new PluginInstance() {
          @Override
          public List<HarnessContributor> harnessContributors() {
            return List.of();
          }

          @Override
          public <T> List<T> contributions(Class<T> type) {
            return PluginInstance.contributionsOf(type, List.of(adapter));
          }

          @Override
          public void close() {}
        });
  }

  private static CanvasFunctionAdapter adapter(CanvasFunctionDefinition... definitions) {
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    when(adapter.functions()).thenReturn(new ArrayList<>(List.of(definitions)));
    when(adapter.enabled()).thenReturn(true);
    return adapter;
  }

  private static CanvasFunctionDefinition definition(String name) {
    return CanvasFunctionDefinition.of(
        name,
        "function " + name,
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }
}
