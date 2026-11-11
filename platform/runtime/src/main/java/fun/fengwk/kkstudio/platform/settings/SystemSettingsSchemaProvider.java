package fun.fengwk.kkstudio.platform.settings;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

import java.util.List;

/**
 * The single ordered metadata source for editable system settings.
 *
 * <p>Field paths intentionally follow the record component paths and group order follows the
 * existing settings cards. Cross-field constraints remain in {@link SystemSettings}'s canonical
 * constructors; this provider only describes individual editable leaves and their controls.
 */
@Component
public class SystemSettingsSchemaProvider {

  private static final SystemSettingsSchemaDTO SCHEMA = createSchema();

  public SystemSettingsSchemaDTO get() {
    return SCHEMA;
  }

  private static SystemSettingsSchemaDTO createSchema() {
    SystemSettingsSchemaDTO schema = new SystemSettingsSchemaDTO();
    schema.setSections(
        List.of(
            aiRuntimeSection(),
            toolSection(),
            environmentSection(),
            backendNetworkSection(),
            storageMediaSection(),
            advancedSection()));
    return schema;
  }

  /** AI 运行时：调用重试、HTTP 错误码、上下文压缩与 subagent 限额。 */
  private static SystemSettingsSchemaDTO.SectionDTO aiRuntimeSection() {
    return section(
        "aiRuntime",
        "settings.tabs.aiRuntime",
        "settings.section.aiRuntime.description",
        retryGroup(),
        httpErrorsGroup(),
        compactionGroup(),
        subagentGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO retryGroup() {
    return group(
        "aiRuntime.retry",
        "settings.section.aiRuntime.retry.title",
        "settings.section.aiRuntime.retry.description",
        field(
            "aiRuntime.retryMaxRetries",
            "settings.field.aiRuntime.retryMaxRetries",
            null,
            FieldType.INTEGER,
            false,
            0,
            null),
        field(
            "aiRuntime.retryBackoffStrategy",
            "settings.field.aiRuntime.retryBackoffStrategy",
            null,
            FieldType.ENUM,
            false,
            null,
            null,
            option("FIXED", "settings.option.retryBackoff.fixed"),
            option("EXPONENTIAL", "settings.option.retryBackoff.exponential")),
        field(
            "aiRuntime.retryBaseDelayMillis",
            "settings.field.aiRuntime.retryBaseDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "aiRuntime.retryMaxDelayMillis",
            "settings.field.aiRuntime.retryMaxDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO httpErrorsGroup() {
    return group(
        "aiRuntime.httpErrors",
        "settings.section.aiRuntime.httpErrors.title",
        "settings.section.aiRuntime.httpErrors.description",
        field(
            "aiRuntime.modelHttpRetryStatusCodes",
            "settings.field.aiRuntime.modelHttpRetryStatusCodes",
            "settings.field.aiRuntime.modelHttpRetryStatusCodes.hint",
            FieldType.INTEGER_LIST,
            false,
            400,
            599));
  }

  /** 上下文压缩：模型引用、保留最近 token 与回复预留 token（PLAN 14.2 文案）。 */
  private static SystemSettingsSchemaDTO.GroupDTO compactionGroup() {
    return group(
        "aiRuntime.compaction",
        "settings.section.aiRuntime.compaction.title",
        "settings.section.aiRuntime.compaction.description",
        field(
            "aiRuntime.compactionModel",
            "settings.field.aiRuntime.compactionModel",
            "settings.field.aiRuntime.compactionModel.hint",
            FieldType.MODEL_SELECTION,
            true,
            null,
            null),
        field(
            "aiRuntime.compactionKeepRecentTokens",
            "settings.field.aiRuntime.compactionKeepRecentTokens",
            null,
            FieldType.INTEGER,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO subagentGroup() {
    return group(
        "aiRuntime.subagent",
        "settings.section.aiRuntime.subagent.title",
        "settings.section.aiRuntime.subagent.description",
        field(
            "aiRuntime.subagentMaxDepth",
            "settings.field.aiRuntime.subagentMaxDepth",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        field(
            "aiRuntime.subagentMaxConcurrency",
            "settings.field.aiRuntime.subagentMaxConcurrency",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        field(
            "aiRuntime.subagentMaxTotalConcurrency",
            "settings.field.aiRuntime.subagentMaxTotalConcurrency",
            null,
            FieldType.INTEGER,
            false,
            0,
            null),
        field(
            "aiRuntime.subagentMaxTurns",
            "settings.field.aiRuntime.subagentMaxTurns",
            null,
            FieldType.INTEGER,
            false,
            1,
            null));
  }

  /** 工具与权限：权限动作与 tool gateway 重试预算。 */
  private static SystemSettingsSchemaDTO.SectionDTO toolSection() {
    return section(
        "tool",
        "settings.tabs.toolsPermissions",
        "settings.section.tool.description",
        permissionGroup(),
        gatewayGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO permissionGroup() {
    return group(
        "tool.permission",
        "settings.section.tool.permission.title",
        "settings.section.tool.permission.description",
        field(
            "tool.permission",
            "settings.section.tool.permission.title",
            "settings.field.tool.permission.hint",
            FieldType.PERMISSION,
            false,
            null,
            null,
            option("allow", "settings.permission.action.allow"),
            option("ask", "settings.permission.action.ask"),
            option("deny", "settings.permission.action.deny")));
  }

  private static SystemSettingsSchemaDTO.GroupDTO gatewayGroup() {
    return group(
        "tool.gateway",
        "settings.section.tool.gateway.title",
        "settings.section.tool.gateway.description",
        field(
            "tool.maxOutputMib",
            "settings.field.tool.maxOutputMib",
            "settings.field.tool.maxOutputMib.hint",
            FieldType.INTEGER,
            false,
            1,
            100),
        field(
            "tool.modelGatewayBusyRetryMillis",
            "settings.field.tool.modelGatewayBusyRetryMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "tool.toolGatewayOverloadRetryMillis",
            "settings.field.tool.toolGatewayOverloadRetryMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  /** 环境：资源上限与心跳超时。 */
  private static SystemSettingsSchemaDTO.SectionDTO environmentSection() {
    return section(
        "environment",
        "settings.tabs.environment",
        "settings.section.environment.description",
        runtimeGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO runtimeGroup() {
    return group(
        "environment.runtime",
        "settings.section.environment.runtime.title",
        "settings.section.environment.runtime.description",
        field(
            "environment.heartbeatTimeoutMillis",
            "settings.field.environment.heartbeatTimeoutMillis",
            null,
            FieldType.LONG,
            false,
            30_000,
            null));
  }

  /** 网络：仅 Backend 出站请求使用的代理。 */
  private static SystemSettingsSchemaDTO.SectionDTO backendNetworkSection() {
    return section(
        "backendNetwork",
        "settings.tabs.backendNetwork",
        "settings.section.backendNetwork.description",
        proxyGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO proxyGroup() {
    return group(
        "backendNetwork.proxy",
        "settings.section.backendNetwork.proxy.title",
        "settings.section.backendNetwork.proxy.description",
        field(
            "backendNetwork.proxyUrl",
            "settings.field.backendNetwork.proxyUrl",
            "settings.field.backendNetwork.proxyUrl.hint",
            FieldType.TEXT,
            true,
            null,
            2048),
        field(
            "backendNetwork.noProxyHosts",
            "settings.field.backendNetwork.noProxyHosts",
            "settings.field.backendNetwork.noProxyHosts.hint",
            FieldType.TEXT,
            false,
            null,
            4096));
  }

  /** 存储与媒体：上传有效期、签名默认值与预览/临时资源预算。 */
  private static SystemSettingsSchemaDTO.SectionDTO storageMediaSection() {
    return section(
        "storageMedia",
        "settings.tabs.storageMedia",
        "settings.section.storageMedia.description",
        uploadGroup(),
        canvasMediaGroup(),
        temporaryResourceGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO uploadGroup() {
    return group(
        "storageMedia.upload",
        "settings.section.storageMedia.upload.title",
        "settings.section.storageMedia.upload.description",
        field(
            "storageMedia.uploadExpiresSeconds",
            "settings.field.storageMedia.uploadExpiresSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "storageMedia.s3PresignDefaultExpiresSeconds",
            "settings.field.storageMedia.s3PresignDefaultExpiresSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "storageMedia.s3PresignMaxExpiresSeconds",
            "settings.field.storageMedia.s3PresignMaxExpiresSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO canvasMediaGroup() {
    return group(
        "storageMedia.canvasMedia",
        "settings.section.storageMedia.canvasMedia.title",
        "settings.section.storageMedia.canvasMedia.description",
        field(
            "storageMedia.canvasMediaProcessTimeoutMillis",
            "settings.field.storageMedia.canvasMediaProcessTimeoutMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "storageMedia.thumbnailMaxDimension",
            "settings.field.storageMedia.thumbnailMaxDimension",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        field(
            "storageMedia.thumbnailQuality",
            "settings.field.storageMedia.thumbnailQuality",
            null,
            FieldType.INTEGER,
            false,
            1,
            100),
        field(
            "storageMedia.imageTier",
            "settings.field.storageMedia.imageTier",
            "settings.field.storageMedia.imageTier.hint",
            FieldType.ENUM,
            false,
            null,
            null,
            option("720P", "settings.option.imageTier.720p"),
            option("1080P", "settings.option.imageTier.1080p"),
            option("ORIGINAL", "settings.option.imageTier.original")));
  }

  private static SystemSettingsSchemaDTO.GroupDTO temporaryResourceGroup() {
    return group(
        "storageMedia.temporaryResource",
        "settings.section.storageMedia.temporaryResource.title",
        "settings.section.storageMedia.temporaryResource.description",
        field(
            "storageMedia.temporaryResourceTtlSeconds",
            "settings.field.storageMedia.temporaryResourceTtlSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "storageMedia.temporaryResourceCleanupIntervalSeconds",
            "settings.field.storageMedia.temporaryResourceCleanupIntervalSeconds",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  /** 高级：资源、处理器与事件总线的进程级预算。 */
  private static SystemSettingsSchemaDTO.SectionDTO advancedSection() {
    return section(
        "advanced",
        "settings.tabs.advanced",
        "settings.section.advanced.description",
        advancedProcessorGroup(),
        advancedApplicationEventGroup(),
        advancedWorkNotificationGroup());
  }

  private static SystemSettingsSchemaDTO.GroupDTO advancedProcessorGroup() {
    return group(
        "advanced.processor",
        "settings.section.advanced.processor.title",
        "settings.section.advanced.processor.description",
        field(
            "advanced.processorLeaseDurationMillis",
            "settings.field.advanced.processorLeaseDurationMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.processorHeartbeatIntervalMillis",
            "settings.field.advanced.processorHeartbeatIntervalMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.threadResolveFailureDelayMillis",
            "settings.field.advanced.threadResolveFailureDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.modelDispatchBusyFallbackDelayMillis",
            "settings.field.advanced.modelDispatchBusyFallbackDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.toolPreflightFailureDelayMillis",
            "settings.field.advanced.toolPreflightFailureDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.toolDispatchBusyFallbackDelayMillis",
            "settings.field.advanced.toolDispatchBusyFallbackDelayMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO advancedApplicationEventGroup() {
    return group(
        "advanced.applicationEvent",
        "settings.section.advanced.applicationEvent.title",
        "settings.section.advanced.applicationEvent.description",
        field(
            "advanced.applicationEventQueueCapacity",
            "settings.field.advanced.applicationEventQueueCapacity",
            null,
            FieldType.INTEGER,
            false,
            1,
            null),
        field(
            "advanced.applicationEventMaxBytes",
            "settings.field.advanced.applicationEventMaxBytes",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.applicationEventSendTimeoutMillis",
            "settings.field.advanced.applicationEventSendTimeoutMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.applicationEventHeartbeatIntervalMillis",
            "settings.field.advanced.applicationEventHeartbeatIntervalMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.GroupDTO advancedWorkNotificationGroup() {
    return group(
        "advanced.workNotification",
        "settings.section.advanced.workNotification.title",
        "settings.section.advanced.workNotification.description",
        field(
            "advanced.postgresqlWorkNotificationPollMillis",
            "settings.field.advanced.postgresqlWorkNotificationPollMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null),
        field(
            "advanced.postgresqlWorkReconnectBackoffMillis",
            "settings.field.advanced.postgresqlWorkReconnectBackoffMillis",
            null,
            FieldType.LONG,
            false,
            1,
            null));
  }

  private static SystemSettingsSchemaDTO.SectionDTO section(
      String key,
      String labelKey,
      String descriptionKey,
      SystemSettingsSchemaDTO.GroupDTO... groups) {
    SystemSettingsSchemaDTO.SectionDTO section = new SystemSettingsSchemaDTO.SectionDTO();
    section.setKey(key);
    section.setLabelKey(labelKey);
    section.setDescriptionKey(descriptionKey);
    section.setGroups(List.of(groups));
    return section;
  }

  private static SystemSettingsSchemaDTO.GroupDTO group(
      String key,
      String labelKey,
      String descriptionKey,
      SystemSettingsSchemaDTO.FieldDTO... fields) {
    SystemSettingsSchemaDTO.GroupDTO group = new SystemSettingsSchemaDTO.GroupDTO();
    group.setKey(key);
    group.setLabelKey(labelKey);
    group.setDescriptionKey(descriptionKey);
    group.setFields(List.of(fields));
    return group;
  }

  private static SystemSettingsSchemaDTO.FieldDTO field(
      String path,
      String labelKey,
      String hintKey,
      FieldType type,
      boolean nullable,
      Integer min,
      Integer max,
      SystemSettingsSchemaDTO.OptionDTO... options) {
    SystemSettingsSchemaDTO.FieldDTO field = new SystemSettingsSchemaDTO.FieldDTO();
    field.setPath(path);
    field.setLabelKey(labelKey);
    field.setHintKey(hintKey != null ? hintKey : labelKey + ".hint");
    field.setType(type);
    field.setNullable(nullable);
    field.setMin(min);
    field.setMax(max);
    field.setOptions(options.length == 0 ? null : List.of(options));
    return field;
  }

  private static SystemSettingsSchemaDTO.OptionDTO option(String value, String labelKey) {
    SystemSettingsSchemaDTO.OptionDTO option = new SystemSettingsSchemaDTO.OptionDTO();
    option.setValue(value);
    option.setLabelKey(labelKey);
    return option;
  }
}
