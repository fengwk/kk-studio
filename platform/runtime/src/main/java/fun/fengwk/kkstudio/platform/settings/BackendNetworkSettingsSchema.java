package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

final class BackendNetworkSettingsSchema {

  static SystemSettingsSchemaDTO.SectionDTO definition() {
    return backendNetworkSection();
  }

  /** 网络：仅 Backend 出站请求使用的代理。 */
  private static SystemSettingsSchemaDTO.SectionDTO backendNetworkSection() {
    return SystemSettingsSchemaDsl.section(
        "backendNetwork",
        "settings.tabs.backendNetwork",
        "settings.section.backendNetwork.description",
        proxyGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO proxyGroup() {
    return SystemSettingsSchemaDsl.group(
        "backendNetwork.proxy",
        "settings.section.backendNetwork.proxy.title",
        "settings.section.backendNetwork.proxy.description",
        SystemSettingsSchemaDsl.field(
            "backendNetwork.proxyUrl",
            "settings.field.backendNetwork.proxyUrl",
            "settings.field.backendNetwork.proxyUrl.hint",
            FieldType.TEXT,
            true,
            null,
            2048),
        SystemSettingsSchemaDsl.field(
            "backendNetwork.noProxyHosts",
            "settings.field.backendNetwork.noProxyHosts",
            "settings.field.backendNetwork.noProxyHosts.hint",
            FieldType.TEXT,
            false,
            null,
            4096));
  }
}
