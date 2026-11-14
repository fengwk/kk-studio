package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.RESOURCE;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.skipUnknown;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.PluginSpec;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.plugin.Plugin;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;

import java.util.List;
import java.util.Optional;

/**
 * 已安装插件条目的读取与预校验。
 *
 * <p>安装身份由依赖决定，不能由文件声明：未安装的 id 按条目级 skip 上报，已安装的用该插件自身的 configurationSchema
 * 解析与校验普通值。非法值与未知（含秘密）字段是硬错误，绝不静默降级或接受秘密。
 */
final class ConfigSyncPluginEntries {

  private final PluginRegistry pluginRegistry;

  ConfigSyncPluginEntries(PluginRegistry pluginRegistry) {
    this.pluginRegistry = pluginRegistry;
  }

  PluginSpec parsePlugin(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String pluginId = entry.reqName("pluginId");
    declared.add(pluginId);
    String valuesJson = entry.reqString("valuesJson");
    Optional<Plugin> plugin = pluginRegistry.find(pluginId);
    String resolvedValues = null;
    if (plugin.isPresent()) {
      // known 字段非法值（类型不符、未声明字段、超限）即使伴随未知字段也必须硬拒绝：与其它类别一样先用写入路径校验，再判定未知字段 skip。
      try {
        resolvedValues = plugin.get().configurationSchema().resolveValues(valuesJson);
      } catch (IllegalArgumentException error) {
        // 底层消息可能含用户值，统一替换为安全的类别与条目名；秘密字段名不得借插件条目夹带。
        throw DomainException.validation(RESOURCE, "plugins entry is invalid: " + pluginId);
      }
    }
    if (skipUnknown(entry, ConfigSyncKind.PLUGINS, pluginId, skipped)) {
      return null;
    }
    if (plugin.isEmpty()) {
      // 安装身份由依赖决定：文件里未安装的 id 按既有条目级 skip 上报，不加载也不合成插件。
      skipped.add(
          new ConfigSyncSkipped(ConfigSyncKind.PLUGINS, pluginId, "plugin is not installed"));
      return null;
    }
    return new PluginSpec(pluginId, resolvedValues);
  }
}
