package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

final class EnvironmentSettingsSchema {

  static SystemSettingsSchemaDTO.SectionDTO definition() {
    return environmentSection();
  }

  /** 环境：资源上限与心跳超时。 */
  private static SystemSettingsSchemaDTO.SectionDTO environmentSection() {
    return SystemSettingsSchemaDsl.section(
        "environment",
        "settings.tabs.environment",
        "settings.section.environment.description",
        runtimeGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO runtimeGroup() {
    return SystemSettingsSchemaDsl.group(
        "environment.runtime",
        "settings.section.environment.runtime.title",
        "settings.section.environment.runtime.description",
        SystemSettingsSchemaDsl.field(
            "environment.heartbeatTimeoutMillis",
            "settings.field.environment.heartbeatTimeoutMillis",
            null,
            FieldType.LONG,
            false,
            30_000,
            null));
  }
}
