package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

final class ToolSettingsSchema {

  static SystemSettingsSchemaDTO.SectionDTO definition() {
    return toolSection();
  }

  /** 工具与权限：权限动作与 tool gateway 重试预算。 */
  private static SystemSettingsSchemaDTO.SectionDTO toolSection() {
    return SystemSettingsSchemaDsl.section(
        "tool",
        "settings.tabs.toolsPermissions",
        "settings.section.tool.description",
        permissionGroup(),
        gatewayGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO permissionGroup() {
    return SystemSettingsSchemaDsl.group(
        "tool.permission",
        "settings.section.tool.permission.title",
        "settings.section.tool.permission.description",
        SystemSettingsSchemaDsl.field(
            "tool.permission",
            "settings.section.tool.permission.title",
            "settings.field.tool.permission.hint",
            FieldType.PERMISSION,
            false,
            null,
            null,
            SystemSettingsSchemaDsl.option("allow", "settings.permission.action.allow"),
            SystemSettingsSchemaDsl.option("ask", "settings.permission.action.ask"),
            SystemSettingsSchemaDsl.option("deny", "settings.permission.action.deny")));
  }

  private static SystemSettingsSchemaDTO.GroupDTO gatewayGroup() {
    return SystemSettingsSchemaDsl.group(
        "tool.gateway",
        "settings.section.tool.gateway.title",
        "settings.section.tool.gateway.description",
        SystemSettingsSchemaDsl.field(
            "tool.maxOutputMib",
            "settings.field.tool.maxOutputMib",
            "settings.field.tool.maxOutputMib.hint",
            FieldType.INTEGER,
            false,
            1,
            100),
        SystemSettingsSchemaDsl.field(
            "tool.modelGatewayBusyRetryMillis",
            "settings.field.tool.modelGatewayBusyRetryMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "tool.toolGatewayOverloadRetryMillis",
            "settings.field.tool.toolGatewayOverloadRetryMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }
}
