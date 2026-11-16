package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

final class AiRuntimeSettingsSchema {

  static SystemSettingsSchemaDTO.SectionDTO definition() {
    return aiRuntimeSection();
  }

  /** AI 运行时：调用重试、HTTP 错误码、上下文压缩与 subagent 限额。 */
  private static SystemSettingsSchemaDTO.SectionDTO aiRuntimeSection() {
    return SystemSettingsSchemaDsl.section(
        "aiRuntime",
        "settings.tabs.aiRuntime",
        "settings.section.aiRuntime.description",
        retryGroup(),
        httpErrorsGroup(),
        compactionGroup(),
        subagentGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO retryGroup() {
    return SystemSettingsSchemaDsl.group(
        "aiRuntime.retry",
        "settings.section.aiRuntime.retry.title",
        "settings.section.aiRuntime.retry.description",
        SystemSettingsSchemaDsl.field(
            "aiRuntime.retryMaxRetries",
            "settings.field.aiRuntime.retryMaxRetries",
            null,
            FieldType.INTEGER,
            false,
            0,
            null),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.retryBackoffStrategy",
            "settings.field.aiRuntime.retryBackoffStrategy",
            null,
            FieldType.ENUM,
            false,
            null,
            null,
            SystemSettingsSchemaDsl.option("FIXED", "settings.option.retryBackoff.fixed"),
            SystemSettingsSchemaDsl.option(
                "EXPONENTIAL", "settings.option.retryBackoff.exponential")),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.retryBaseDelayMillis",
            "settings.field.aiRuntime.retryBaseDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.retryMaxDelayMillis",
            "settings.field.aiRuntime.retryMaxDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO httpErrorsGroup() {
    return SystemSettingsSchemaDsl.group(
        "aiRuntime.httpErrors",
        "settings.section.aiRuntime.httpErrors.title",
        "settings.section.aiRuntime.httpErrors.description",
        SystemSettingsSchemaDsl.field(
            "aiRuntime.modelHttpRetryStatusCodes",
            "settings.field.aiRuntime.modelHttpRetryStatusCodes",
            "settings.field.aiRuntime.modelHttpRetryStatusCodes.hint",
            FieldType.INTEGER_LIST,
            false,
            400,
            599));
  }

  /** 上下文压缩：压缩使用的模型引用与保留的最近 token 数。 */
  private static SystemSettingsSchemaDTO.GroupDTO compactionGroup() {
    return SystemSettingsSchemaDsl.group(
        "aiRuntime.compaction",
        "settings.section.aiRuntime.compaction.title",
        "settings.section.aiRuntime.compaction.description",
        SystemSettingsSchemaDsl.field(
            "aiRuntime.compactionModel",
            "settings.field.aiRuntime.compactionModel",
            "settings.field.aiRuntime.compactionModel.hint",
            FieldType.MODEL_SELECTION,
            true,
            null,
            null),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.compactionKeepRecentTokens",
            "settings.field.aiRuntime.compactionKeepRecentTokens",
            null,
            FieldType.INTEGER,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO subagentGroup() {
    return SystemSettingsSchemaDsl.group(
        "aiRuntime.subagent",
        "settings.section.aiRuntime.subagent.title",
        "settings.section.aiRuntime.subagent.description",
        SystemSettingsSchemaDsl.field(
            "aiRuntime.subagentMaxDepth",
            "settings.field.aiRuntime.subagentMaxDepth",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.subagentMaxConcurrency",
            "settings.field.aiRuntime.subagentMaxConcurrency",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.subagentMaxTotalConcurrency",
            "settings.field.aiRuntime.subagentMaxTotalConcurrency",
            null,
            FieldType.INTEGER,
            false,
            0,
            null),
        SystemSettingsSchemaDsl.field(
            "aiRuntime.subagentMaxTurns",
            "settings.field.aiRuntime.subagentMaxTurns",
            null,
            FieldType.INTEGER,
            false,
            1,
            null));
  }
}
