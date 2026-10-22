package fun.fengwk.kkstudio.platform.settings;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.ApplyTiming;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.FieldType;

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
            section(
                "aiRuntime",
                "settings.tabs.aiRuntime",
                "settings.section.aiRuntime.description",
                false,
                group(
                    "aiRuntime.retry",
                    "settings.section.aiRuntime.retry.title",
                    "settings.section.aiRuntime.retry.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
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
                        null)),
                group(
                    "aiRuntime.httpErrors",
                    "settings.section.aiRuntime.httpErrors.title",
                    "settings.section.aiRuntime.httpErrors.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
                    field(
                        "aiRuntime.modelHttpRetryStatusCodes",
                        "settings.field.aiRuntime.modelHttpRetryStatusCodes",
                        "settings.field.aiRuntime.modelHttpRetryStatusCodes.hint",
                        FieldType.INTEGER_LIST,
                        false,
                        400,
                        599)),
                group(
                    "aiRuntime.modelCatalog",
                    "settings.section.aiRuntime.modelCatalog.title",
                    "settings.section.aiRuntime.modelCatalog.description",
                    false,
                    ApplyTiming.NEXT_HTTP_REQUEST,
                    field(
                        "aiRuntime.modelCatalogSource",
                        "settings.field.aiRuntime.modelCatalogSource",
                        "settings.field.aiRuntime.modelCatalogSource.hint",
                        FieldType.ENUM,
                        false,
                        null,
                        null,
                        option("models_dev_v2", "settings.option.modelCatalogSource.modelsDevV2"),
                        option("models_dev_v1", "settings.option.modelCatalogSource.modelsDevV1"))),
                group(
                    "aiRuntime.compaction",
                    "settings.section.aiRuntime.compaction.title",
                    "settings.section.aiRuntime.compaction.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
                    field(
                        "aiRuntime.compactionKeepRecentTokens",
                        "settings.field.aiRuntime.compactionKeepRecentTokens",
                        null,
                        FieldType.INTEGER,
                        false,
                        1,
                        null)),
                group(
                    "aiRuntime.subagent",
                    "settings.section.aiRuntime.subagent.title",
                    "settings.section.aiRuntime.subagent.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
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
                        null))),
            section(
                "tool",
                "settings.tabs.toolsPermissions",
                "settings.section.tool.description",
                false,
                group(
                    "tool.permission",
                    "settings.section.tool.permission.title",
                    "settings.section.tool.permission.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
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
                        option("deny", "settings.permission.action.deny"))),
                group(
                    "tool.gateway",
                    "settings.section.tool.gateway.title",
                    "settings.section.tool.gateway.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
                    field(
                        "tool.modelGatewayBusyRetryMillis",
                        "settings.field.tool.modelGatewayBusyRetryMillis",
                        null,
                        FieldType.LONG,
                        false,
                        1,
                        null),
                    field(
                        "tool.toolGatewayBusyRetryMillis",
                        "settings.field.tool.toolGatewayBusyRetryMillis",
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
                        null))),
            section(
                "environment",
                "settings.tabs.environment",
                "settings.section.environment.description",
                false,
                group(
                    "environment.runtime",
                    "settings.section.environment.runtime.title",
                    "settings.section.environment.runtime.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
                    field(
                        "environment.maxResourceBytes",
                        "settings.field.environment.maxResourceBytes",
                        null,
                        FieldType.LONG,
                        false,
                        1,
                        null),
                    field(
                        "environment.heartbeatTimeoutMillis",
                        "settings.field.environment.heartbeatTimeoutMillis",
                        null,
                        FieldType.LONG,
                        false,
                        1,
                        null))),
            section(
                "network",
                "settings.tabs.network",
                "settings.section.network.description",
                false,
                group(
                    "network.proxy",
                    "settings.section.network.proxy.title",
                    "settings.section.network.proxy.description",
                    false,
                    ApplyTiming.NEXT_HTTP_REQUEST,
                    field(
                        "network.proxyUrl",
                        "settings.field.network.proxyUrl",
                        "settings.field.network.proxyUrl.hint",
                        FieldType.TEXT,
                        true,
                        null,
                        2048),
                    field(
                        "network.noProxyHosts",
                        "settings.field.network.noProxyHosts",
                        "settings.field.network.noProxyHosts.hint",
                        FieldType.TEXT,
                        false,
                        null,
                        4096))),
            section(
                "storageMedia",
                "settings.tabs.storageMedia",
                "settings.section.storageMedia.description",
                true,
                group(
                    "storageMedia.upload",
                    "settings.section.storageMedia.upload.title",
                    "settings.section.storageMedia.upload.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
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
                        null)),
                group(
                    "storageMedia.canvasMedia",
                    "settings.section.storageMedia.canvasMedia.title",
                    "settings.section.storageMedia.canvasMedia.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
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
                        100)),
                group(
                    "storageMedia.temporaryResource",
                    "settings.section.storageMedia.temporaryResource.title",
                    "settings.section.storageMedia.temporaryResource.description",
                    false,
                    ApplyTiming.NEXT_INVOCATION,
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
                        null))),
            section(
                "advanced",
                "settings.tabs.advanced",
                "settings.section.advanced.description",
                true,
                group(
                    "advanced.resource",
                    "settings.section.advanced.resource.title",
                    "settings.section.advanced.resource.description",
                    true,
                    null,
                    field(
                        "advanced.resourceMaxBytes",
                        "settings.field.advanced.resourceMaxBytes",
                        null,
                        FieldType.LONG,
                        false,
                        1,
                        null)),
                group(
                    "advanced.processor",
                    "settings.section.advanced.processor.title",
                    "settings.section.advanced.processor.description",
                    true,
                    null,
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
                        null)),
                group(
                    "advanced.applicationEvent",
                    "settings.section.advanced.applicationEvent.title",
                    "settings.section.advanced.applicationEvent.description",
                    true,
                    null,
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
                        null)),
                group(
                    "advanced.workNotification",
                    "settings.section.advanced.workNotification.title",
                    "settings.section.advanced.workNotification.description",
                    true,
                    null,
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
                        null)))));
    return schema;
  }

  private static SystemSettingsSchemaDTO.SectionDTO section(
      String key,
      String labelKey,
      String descriptionKey,
      boolean restartRequired,
      SystemSettingsSchemaDTO.GroupDTO... groups) {
    SystemSettingsSchemaDTO.SectionDTO section = new SystemSettingsSchemaDTO.SectionDTO();
    section.setKey(key);
    section.setLabelKey(labelKey);
    section.setDescriptionKey(descriptionKey);
    section.setRestartRequired(restartRequired);
    section.setGroups(List.of(groups));
    return section;
  }

  private static SystemSettingsSchemaDTO.GroupDTO group(
      String key,
      String labelKey,
      String descriptionKey,
      boolean restartRequired,
      ApplyTiming applyTiming,
      SystemSettingsSchemaDTO.FieldDTO... fields) {
    SystemSettingsSchemaDTO.GroupDTO group = new SystemSettingsSchemaDTO.GroupDTO();
    group.setKey(key);
    group.setLabelKey(labelKey);
    group.setDescriptionKey(descriptionKey);
    group.setRestartRequired(restartRequired);
    group.setApplyTiming(applyTiming);
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
