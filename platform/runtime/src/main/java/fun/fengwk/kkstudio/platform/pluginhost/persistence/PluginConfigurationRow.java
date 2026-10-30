package fun.fengwk.kkstudio.platform.pluginhost.persistence;

/** 一个已安装 Plugin 的当前普通配置行。 */
public record PluginConfigurationRow(String pluginId, long revision, String valuesJson) {}
