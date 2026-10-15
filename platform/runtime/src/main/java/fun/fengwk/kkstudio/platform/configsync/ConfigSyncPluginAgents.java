package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;

import java.util.Set;

/**
 * 插件普通配置所声明的 Agent 依赖的唯一读取入口。
 *
 * <p>依赖图与导入计划都必须通过同一份 {@link StudioPlugin#requiredAgentNames(PluginConfig)} 声明解析引用的 Agent 名称，绝不按
 * pluginId 或字段路径猜测；只有安装目录中的插件才可解析，权威目录与安装集合本应一致，漂移即失败。
 */
final class ConfigSyncPluginAgents {

  private ConfigSyncPluginAgents() {}

  /** 该插件在给定普通配置下声明的 Agent 名称；{@code revision} 只用于构造配置载体，语义由插件自行决定。 */
  static Set<String> requiredAgentNames(
      StudioPluginRegistry registry, String pluginId, long revision, String valuesJson) {
    StudioPlugin plugin =
        registry
            .find(pluginId)
            .orElseThrow(() -> new IllegalStateException("plugin is not installed: " + pluginId));
    return Set.copyOf(plugin.requiredAgentNames(new PluginConfig(revision, valuesJson)));
  }
}
