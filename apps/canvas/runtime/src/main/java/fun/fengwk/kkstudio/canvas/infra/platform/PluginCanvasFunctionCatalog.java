package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogSnapshot;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次快照构建期冻结的 Canvas 目录视图：插件实例贡献的 adapter 与内置 adapter 合并成唯一目录，并按适配器身份索引每个插件对外暴露的函数名。
 *
 * <p>视图由 web 组合根作为产品目录投影注入 {@code
 * PluginCatalogRuntime}，因此每个快照只构建一次：目录校验失败会让快照构建失败并回收候选实例，租用快照的执行路径只读已冻结 目录，不重复读取 adapter 定义。插件归属只按
 * adapter 实例身份记录，内置 adapter 不属于任何插件。
 */
public final class PluginCanvasFunctionCatalog {

  private final CanvasFunctionCatalog catalog;
  private final Map<String, List<String>> functionNamesByPlugin;

  private PluginCanvasFunctionCatalog(
      CanvasFunctionCatalog catalog, Map<String, List<String>> functionNamesByPlugin) {
    this.catalog = catalog;
    this.functionNamesByPlugin = functionNamesByPlugin;
  }

  /**
   * 把快照中每个插件实例贡献的 adapter 与内置 adapter 冻结成唯一目录。
   *
   * @throws IllegalArgumentException 任一 adapter 未声明函数、函数定义非法或函数名重复
   */
  public static PluginCanvasFunctionCatalog from(
      PluginCatalogSnapshot snapshot, List<CanvasFunctionAdapter> builtins) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(builtins, "builtins");
    List<CanvasFunctionAdapter> adapters = new ArrayList<>(builtins);
    Map<CanvasFunctionAdapter, String> pluginByAdapter = new IdentityHashMap<>();
    for (PluginCatalogSnapshot.ConfiguredPlugin plugin : snapshot.configuredPlugins()) {
      String pluginId = plugin.descriptor().pluginId();
      for (CanvasFunctionAdapter adapter :
          plugin.instance().contributions(CanvasFunctionAdapter.class)) {
        adapters.add(adapter);
        pluginByAdapter.put(adapter, pluginId);
      }
    }
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(adapters);
    Map<String, List<String>> namesByPlugin = new LinkedHashMap<>();
    for (CanvasFunctionCatalog.RegisteredFunction registered : catalog.list()) {
      String pluginId = pluginByAdapter.get(registered.adapter());
      if (pluginId != null) {
        namesByPlugin
            .computeIfAbsent(pluginId, id -> new ArrayList<>())
            .add(registered.function().name());
      }
    }
    Map<String, List<String>> frozenNames = new LinkedHashMap<>();
    namesByPlugin.forEach((pluginId, names) -> frozenNames.put(pluginId, List.copyOf(names)));
    return new PluginCanvasFunctionCatalog(catalog, Map.copyOf(frozenNames));
  }

  /** 读取快照中已冻结的 Canvas 目录视图。 */
  public static PluginCanvasFunctionCatalog of(PluginCatalogSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return snapshot.productCatalog(PluginCanvasFunctionCatalog.class);
  }

  /** 本次快照的 Canvas 函数目录。 */
  public CanvasFunctionCatalog catalog() {
    return catalog;
  }

  /** 某插件在冻结目录中对外暴露的函数名，顺序与目录一致；未贡献时为不可变空列表。 */
  public List<String> functionNames(String pluginId) {
    Objects.requireNonNull(pluginId, "pluginId");
    return functionNamesByPlugin.getOrDefault(pluginId, List.of());
  }

  /**
   * 管理面的函数名投影：平台只提供快照与已配置插件，Canvas 从已冻结目录按 adapter 归属返回名称。
   *
   * <p>方法签名即 {@code PluginFunctionNamesProjection}，由 web 组合根注册为该端口。
   */
  public static List<String> functionNames(
      PluginCatalogSnapshot snapshot, PluginCatalogSnapshot.ConfiguredPlugin plugin) {
    Objects.requireNonNull(plugin, "plugin");
    return of(snapshot).functionNames(plugin.descriptor().pluginId());
  }
}
