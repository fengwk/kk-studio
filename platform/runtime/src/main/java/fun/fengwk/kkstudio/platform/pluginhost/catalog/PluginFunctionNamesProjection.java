package fun.fengwk.kkstudio.platform.pluginhost.catalog;

import java.util.List;

/**
 * 冻结产品目录对单个插件贡献的函数名投影。
 *
 * <p>平台只把当前快照与已配置插件传进来，不 import 也不解释产品类型；实现由拥有该产品目录的模块提供，并从已冻结目录按贡献身份返回名称，因此一次租用里不会重复读取 adapter
 * 定义。这里没有目录实现时（例如平台自身的测试装配）投影固定为空列表。
 */
@FunctionalInterface
public interface PluginFunctionNamesProjection {

  /** 默认投影：平台不知道任何产品目录，因此不对外暴露函数名。 */
  PluginFunctionNamesProjection NONE = (snapshot, plugin) -> List.of();

  /**
   * 该插件在冻结产品目录中对外暴露的函数名，顺序由该目录冻结。
   *
   * @param snapshot 本次租用的快照
   * @param plugin 已配置插件
   */
  List<String> functionNames(
      PluginCatalogSnapshot snapshot, PluginCatalogSnapshot.ConfiguredPlugin plugin);
}
