package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;

import java.util.Set;

/**
 * 插件普通配置所声明的 Agent 依赖的唯一读取入口。
 *
 * <p>依赖图、导入计划与事务内重检都必须通过同一份 {@link StudioPlugin#requiredAgentNames(PluginConfig)} 声明解析引用的 Agent
 * 名称，绝不按 pluginId 或字段路径猜测；只有安装目录中的插件才可解析，权威目录与安装集合本应一致，漂移即失败。
 *
 * <p>插件拒绝自身普通配置时抛出的 {@link IllegalArgumentException} 是已知的输入边界：统一收敛为固定去敏的 {@link
 * AiValidationException}，不串联 cause、不回显配置值。其它异常（含未知运行时错误与致命错误）照常上抛，绝不伪装成校验失败。
 */
final class ConfigSyncPluginAgents {

  private static final String RESOURCE = "config_sync";

  private ConfigSyncPluginAgents() {}

  /** 该插件在给定普通配置下声明的 Agent 名称；{@code revision} 只用于构造配置载体，语义由插件自行决定。 */
  static Set<String> requiredAgentNames(
      StudioPluginRegistry registry, String pluginId, long revision, String valuesJson) {
    StudioPlugin plugin =
        registry
            .find(pluginId)
            .orElseThrow(() -> new IllegalStateException("plugin is not installed: " + pluginId));
    Set<String> names;
    try {
      names = plugin.requiredAgentNames(new PluginConfig(revision, valuesJson));
    } catch (IllegalArgumentException error) {
      // 固定文案：插件拒绝该普通配置只说明输入无效，绝不回显配置值也不串联底层消息。
      throw new AiValidationException(RESOURCE, "plugin configuration is invalid: " + pluginId);
    }
    return Set.copyOf(names);
  }
}
