package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

final class StorageMediaSettingsSchema {

  static SystemSettingsSchemaDTO.SectionDTO definition() {
    return storageMediaSection();
  }

  /** 存储与媒体：上传有效期、签名默认值与预览/临时资源预算。 */
  private static SystemSettingsSchemaDTO.SectionDTO storageMediaSection() {
    return SystemSettingsSchemaDsl.section(
        "storageMedia",
        "settings.tabs.storageMedia",
        "settings.section.storageMedia.description",
        uploadGroup(),
        imageInputGroup(),
        canvasMediaGroup(),
        temporaryResourceGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO uploadGroup() {
    return SystemSettingsSchemaDsl.group(
        "storageMedia.upload",
        "settings.section.storageMedia.upload.title",
        "settings.section.storageMedia.upload.description",
        SystemSettingsSchemaDsl.field(
            "storageMedia.uploadExpiresSeconds",
            "settings.field.storageMedia.uploadExpiresSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "storageMedia.s3PresignDefaultExpiresSeconds",
            "settings.field.storageMedia.s3PresignDefaultExpiresSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "storageMedia.s3PresignMaxExpiresSeconds",
            "settings.field.storageMedia.s3PresignMaxExpiresSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO imageInputGroup() {
    return SystemSettingsSchemaDsl.group(
        "storageMedia.imageInput",
        "settings.section.storageMedia.imageInput.title",
        "settings.section.storageMedia.imageInput.description",
        SystemSettingsSchemaDsl.field(
            "storageMedia.imageTier",
            "settings.field.storageMedia.imageTier",
            "settings.field.storageMedia.imageTier.hint",
            FieldType.ENUM,
            false,
            null,
            null,
            SystemSettingsSchemaDsl.option("720P", "settings.option.imageTier.720p"),
            SystemSettingsSchemaDsl.option("1080P", "settings.option.imageTier.1080p"),
            SystemSettingsSchemaDsl.option("ORIGINAL", "settings.option.imageTier.original")),
        SystemSettingsSchemaDsl.field(
            "storageMedia.maxImagesPerRequest",
            "settings.field.storageMedia.maxImagesPerRequest",
            "settings.field.storageMedia.maxImagesPerRequest.hint",
            FieldType.INTEGER,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO canvasMediaGroup() {
    return SystemSettingsSchemaDsl.group(
        "storageMedia.canvasMedia",
        "settings.section.storageMedia.canvasMedia.title",
        "settings.section.storageMedia.canvasMedia.description",
        SystemSettingsSchemaDsl.field(
            "storageMedia.canvasMediaProcessTimeoutMillis",
            "settings.field.storageMedia.canvasMediaProcessTimeoutMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "storageMedia.thumbnailMaxDimension",
            "settings.field.storageMedia.thumbnailMaxDimension",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "storageMedia.thumbnailQuality",
            "settings.field.storageMedia.thumbnailQuality",
            null,
            FieldType.INTEGER,
            false,
            1,
            100));
  }

  private static SystemSettingsSchemaDTO.GroupDTO temporaryResourceGroup() {
    return SystemSettingsSchemaDsl.group(
        "storageMedia.temporaryResource",
        "settings.section.storageMedia.temporaryResource.title",
        "settings.section.storageMedia.temporaryResource.description",
        SystemSettingsSchemaDsl.field(
            "storageMedia.temporaryResourceTtlSeconds",
            "settings.field.storageMedia.temporaryResourceTtlSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        SystemSettingsSchemaDsl.field(
            "storageMedia.temporaryResourceCleanupIntervalSeconds",
            "settings.field.storageMedia.temporaryResourceCleanupIntervalSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }
}
