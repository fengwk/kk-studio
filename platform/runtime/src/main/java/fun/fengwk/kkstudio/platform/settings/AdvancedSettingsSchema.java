package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

final class AdvancedSettingsSchema {

  static SystemSettingsSchemaDTO.SectionDTO definition() {
    return advancedSection();
  }

  /** 高级：资源、处理器与事件总线的进程级预算。 */
  private static SystemSettingsSchemaDTO.SectionDTO advancedSection() {
    return SystemSettingsSchemaDsl.section(
        "advanced",
        "settings.tabs.advanced",
        "settings.section.advanced.description",
        advancedProcessorGroup(),
        advancedApplicationEventGroup(),
        advancedWorkNotificationGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO advancedProcessorGroup() {
    return SystemSettingsSchemaDsl.group(
        "advanced.processor",
        "settings.section.advanced.processor.title",
        "settings.section.advanced.processor.description",
        SystemSettingsSchemaDsl.field(
            "advanced.processorLeaseDurationMillis",
            "settings.field.advanced.processorLeaseDurationMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.processorHeartbeatIntervalMillis",
            "settings.field.advanced.processorHeartbeatIntervalMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.threadResolveFailureDelayMillis",
            "settings.field.advanced.threadResolveFailureDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.modelDispatchBusyFallbackDelayMillis",
            "settings.field.advanced.modelDispatchBusyFallbackDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.toolPreflightFailureDelayMillis",
            "settings.field.advanced.toolPreflightFailureDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.toolDispatchBusyFallbackDelayMillis",
            "settings.field.advanced.toolDispatchBusyFallbackDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO advancedApplicationEventGroup() {
    return SystemSettingsSchemaDsl.group(
        "advanced.applicationEvent",
        "settings.section.advanced.applicationEvent.title",
        "settings.section.advanced.applicationEvent.description",
        SystemSettingsSchemaDsl.field(
            "advanced.applicationEventQueueCapacity",
            "settings.field.advanced.applicationEventQueueCapacity",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.applicationEventMaxBytes",
            "settings.field.advanced.applicationEventMaxBytes",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.applicationEventSendTimeoutMillis",
            "settings.field.advanced.applicationEventSendTimeoutMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.applicationEventHeartbeatIntervalMillis",
            "settings.field.advanced.applicationEventHeartbeatIntervalMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO advancedWorkNotificationGroup() {
    return SystemSettingsSchemaDsl.group(
        "advanced.workNotification",
        "settings.section.advanced.workNotification.title",
        "settings.section.advanced.workNotification.description",
        SystemSettingsSchemaDsl.field(
            "advanced.postgresqlWorkNotificationPollMillis",
            "settings.field.advanced.postgresqlWorkNotificationPollMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "advanced.postgresqlWorkReconnectBackoffMillis",
            "settings.field.advanced.postgresqlWorkReconnectBackoffMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }
}
