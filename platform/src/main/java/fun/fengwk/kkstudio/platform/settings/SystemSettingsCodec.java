package fun.fengwk.kkstudio.platform.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionKeyValidator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAdvancedDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAiRuntimeDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsEnvironmentDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsIntegrationsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsStorageMediaDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsToolDTO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * System settings 聚合的唯一 JSON 边界。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>{@link #decode}：严格解码持久化的 canonical JSON（未知字段、尾随 token、错误类型、缺失/null required 字段、Boolean
 *       标量强制转换全部拒绝），失败即存储数据损坏；
 *   <li>{@link #encode}：把聚合编码为确定性 canonical JSON（键排序、map 键排序、省略 null），语义从不依赖键顺序；
 *   <li>{@link #fromDto} / {@link #toSections}：share 层 DTO 与领域聚合之间的显式双向映射，DTO 缺失字段给出精确的 field 级错误。
 * </ul>
 *
 * <p>所有范围/跨字段/启用前提校验都在 {@link SystemSettings} 的 canonical constructor 中执行；本类只负责 JSON 形状与 DTO 映射。
 */
@Component
public class SystemSettingsCodec {

  private final ObjectMapper canonicalMapper;
  private final ObjectReader settingsReader;

  public SystemSettingsCodec() {
    // canonical 存储格式与 HTTP 序列化格式是两个独立契约：convention 的 ObjectMapper 会把 long/Long 输出为十进制
    // 字符串（为前端友好），而 config 的 canonical JSON 明确要求时长/字节为数值。因此 codec 使用独立的 plain mapper，
    // 避免该定制泄漏进持久化格式或让解码把字符串数值当合法输入。
    // codec 无外部依赖，使用无参构造函数，不注入任何 ObjectMapper。
    JsonMapper strictMapper =
        JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // 显式 null 的 required primitive 必须拒绝，而不是回退到 0/false。
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .withCoercionConfig(
                LogicalType.Integer,
                config ->
                    config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            // 布尔只接受 JSON true/false：string/整数/浮点标量一律拒绝（jackson 2.19.0 默认允许 string->boolean 与
            // 0/1->boolean）。
            .withCoercionConfig(
                LogicalType.Boolean,
                config ->
                    config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Textual,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
    this.settingsReader = strictMapper.readerFor(SystemSettings.class);

    this.canonicalMapper =
        JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    this.canonicalMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.canonicalMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    // PermissionAction 的 wire 值是小写 allow/ask/deny（与 ToolSettingsCodec 一致），而非枚举名。
    SimpleModule permissionModule = new SimpleModule();
    permissionModule.addSerializer(
        PermissionAction.class,
        new JsonSerializer<PermissionAction>() {
          @Override
          public void serialize(
              PermissionAction value, JsonGenerator generator, SerializerProvider provider)
              throws IOException {
            generator.writeString(value.value());
          }
        });
    this.canonicalMapper.registerModule(permissionModule);
  }

  /** 严格解码持久化的 canonical JSON 为领域聚合。 */
  public SystemSettings decode(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalStateException("stored system settings config is blank");
    }
    try {
      SystemSettings settings = settingsReader.readValue(json);
      assertRequiredFieldsStored(settings, json);
      return settings;
    } catch (JsonProcessingException error) {
      throw new IllegalStateException(
          "stored system settings config is invalid: " + rootMessage(error), rootCause(error));
    }
  }

  /**
   * 校验存储 JSON 携带聚合 canonical 重编码所需的全部字段。required primitive 缺失时会被解码为 0/false 默认值，其 canonical
   * 重编码与存储树对比即暴露形状差异；null 引用字段被 canonical 编码省略，属合法省略，不受影响。
   *
   * <p>不启用 FAIL_ON_MISSING_CREATOR_PROPERTIES / FAIL_ON_NULL_CREATOR_PROPERTIES：jackson 2.19.0
   * 下这两个开关会连同 nullable 引用 creator 属性一起拒绝，破坏 canonical 的省略 null 语义；因此以已解码聚合的 canonical 重编码定义必须存在的字段。
   */
  private void assertRequiredFieldsStored(SystemSettings settings, String storedJson)
      throws JsonProcessingException {
    JsonNode stored = canonicalMapper.readTree(storedJson);
    JsonNode required = canonicalMapper.valueToTree(settings);
    String missing = firstMissingRequiredField(required, stored);
    if (missing != null) {
      throw new IllegalStateException(
          "stored system settings config is invalid: missing required field " + missing);
    }
  }

  /** 返回 required 树中在 actual 树里缺失或为 null 的第一个字段路径；全部覆盖返回 null。 */
  private static String firstMissingRequiredField(JsonNode required, JsonNode actual) {
    if (required.isObject()) {
      if (!actual.isObject()) {
        return null;
      }
      for (Map.Entry<String, JsonNode> field : required.properties()) {
        JsonNode actualValue = actual.get(field.getKey());
        if (actualValue == null || actualValue.isNull()) {
          return field.getKey();
        }
        String nested = firstMissingRequiredField(field.getValue(), actualValue);
        if (nested != null) {
          return field.getKey() + "." + nested;
        }
      }
    }
    return null;
  }

  /** 把领域聚合编码为确定性 canonical JSON。 */
  public String encode(SystemSettings settings) {
    try {
      return canonicalMapper.writeValueAsString(Objects.requireNonNull(settings, "settings"));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode system settings", error);
    }
  }

  /** 校验并转换 PUT 请求的六个 section 为领域聚合；缺失字段给出 field 级错误。 */
  public SystemSettings fromDto(SystemSettingsSectionsDTO sections) {
    if (sections == null) {
      throw new IllegalArgumentException("system settings are required");
    }
    return new SystemSettings(
        toTool(sections.getTool()),
        toAiRuntime(sections.getAiRuntime()),
        toEnvironment(sections.getEnvironment()),
        toIntegrations(sections.getIntegrations()),
        toStorageMedia(sections.getStorageMedia()),
        toAdvanced(sections.getAdvanced()));
  }

  /** 把领域聚合六个 section 映射为 DTO 载体（GET 响应 / 测试对比）。 */
  public SystemSettingsSectionsDTO toSections(SystemSettings settings) {
    Objects.requireNonNull(settings, "settings");
    SystemSettingsSectionsDTO dto = new SystemSettingsSectionsDTO();
    dto.setTool(fromTool(settings.tool()));
    dto.setAiRuntime(fromAiRuntime(settings.aiRuntime()));
    dto.setEnvironment(fromEnvironment(settings.environment()));
    dto.setIntegrations(fromIntegrations(settings.integrations()));
    dto.setStorageMedia(fromStorageMedia(settings.storageMedia()));
    dto.setAdvanced(fromAdvanced(settings.advanced()));
    return dto;
  }

  private static SystemSettings.Tool toTool(SystemSettingsToolDTO dto) {
    if (dto == null) {
      throw new IllegalArgumentException("tool is required");
    }
    return new SystemSettings.Tool(
        toPermission(dto.getPermission()),
        requiredBoolean(dto.getDefaultYolo(), "tool.defaultYolo"),
        requiredMillis(dto.getModelGatewayBusyRetryMillis(), "tool.modelGatewayBusyRetryMillis"),
        requiredMillis(dto.getToolGatewayBusyRetryMillis(), "tool.toolGatewayBusyRetryMillis"),
        requiredMillis(
            dto.getToolGatewayOverloadRetryMillis(), "tool.toolGatewayOverloadRetryMillis"),
        requiredMillis(dto.getSkillLoadTimeoutMillis(), "tool.skillLoadTimeoutMillis"));
  }

  private static SystemSettings.AiRuntime toAiRuntime(SystemSettingsAiRuntimeDTO dto) {
    if (dto == null) {
      throw new IllegalArgumentException("aiRuntime is required");
    }
    InvocationRetryBackoffStrategy strategy =
        InvocationRetryBackoffStrategy.fromValue(
            requiredText(dto.getRetryBackoffStrategy(), "aiRuntime.retryBackoffStrategy"));
    return new SystemSettings.AiRuntime(
        requiredInt(dto.getRetryMaxRetries(), "aiRuntime.retryMaxRetries"),
        strategy,
        requiredMillis(dto.getRetryBaseDelayMillis(), "aiRuntime.retryBaseDelayMillis"),
        requiredMillis(dto.getRetryMaxDelayMillis(), "aiRuntime.retryMaxDelayMillis"),
        requiredInt(dto.getCompactionKeepRecentTokens(), "aiRuntime.compactionKeepRecentTokens"),
        toModelSelection(dto.getCompactionFallbackModel()),
        requiredInt(dto.getSubagentMaxDepth(), "aiRuntime.subagentMaxDepth"),
        requiredInt(dto.getSubagentMaxConcurrency(), "aiRuntime.subagentMaxConcurrency"),
        requiredInt(dto.getSubagentMaxTotalConcurrency(), "aiRuntime.subagentMaxTotalConcurrency"),
        requiredMillis(dto.getSubagentIdleTimeoutMillis(), "aiRuntime.subagentIdleTimeoutMillis"),
        requiredInt(dto.getSubagentMaxTurns(), "aiRuntime.subagentMaxTurns"));
  }

  private static SystemSettings.Environment toEnvironment(SystemSettingsEnvironmentDTO dto) {
    if (dto == null) {
      throw new IllegalArgumentException("environment is required");
    }
    return new SystemSettings.Environment(
        requiredMillis(dto.getMaxResourceBytes(), "environment.maxResourceBytes"),
        requiredMillis(dto.getHeartbeatTimeoutMillis(), "environment.heartbeatTimeoutMillis"),
        requiredMillis(
            dto.getDirectoryListTimeoutMillis(), "environment.directoryListTimeoutMillis"));
  }

  private static SystemSettings.Integrations toIntegrations(SystemSettingsIntegrationsDTO dto) {
    if (dto == null) {
      throw new IllegalArgumentException("integrations is required");
    }
    SystemSettingsIntegrationsDTO.ComfyuiDTO comfyui = dto.getComfyui();
    SystemSettingsIntegrationsDTO.OpenCliHubDTO openCliHub = dto.getOpenCliHub();
    SystemSettingsIntegrationsDTO.SeedanceDTO seedance = dto.getSeedance();
    SystemSettingsIntegrationsDTO.GptImage2DTO gptImage2 = dto.getGptImage2();
    SystemSettingsIntegrationsDTO.MiniMaxH3DTO minimaxH3 = dto.getMinimaxH3();
    if (comfyui == null
        || openCliHub == null
        || seedance == null
        || gptImage2 == null
        || minimaxH3 == null) {
      throw new IllegalArgumentException(
          "integrations.comfyui, openCliHub, seedance, gptImage2 and minimaxH3 are required");
    }
    return new SystemSettings.Integrations(
        toComfyui(comfyui),
        toOpenCliHub(openCliHub),
        toSeedance(seedance),
        toGptImage2(gptImage2),
        toMiniMaxH3(minimaxH3));
  }

  private static SystemSettings.Comfyui toComfyui(SystemSettingsIntegrationsDTO.ComfyuiDTO dto) {
    return new SystemSettings.Comfyui(
        requiredBoolean(dto.getEnabled(), "integrations.comfyui.enabled"),
        dto.getBaseUrl(),
        requiredMillis(dto.getConnectTimeoutMillis(), "integrations.comfyui.connectTimeoutMillis"),
        requiredMillis(dto.getReadTimeoutMillis(), "integrations.comfyui.readTimeoutMillis"),
        requiredMillis(
            dto.getWebsocketTimeoutMillis(), "integrations.comfyui.websocketTimeoutMillis"),
        requiredMillis(dto.getMaxInputFileBytes(), "integrations.comfyui.maxInputFileBytes"));
  }

  private static SystemSettings.OpenCliHub toOpenCliHub(
      SystemSettingsIntegrationsDTO.OpenCliHubDTO dto) {
    return new SystemSettings.OpenCliHub(
        requiredBoolean(dto.getEnabled(), "integrations.openCliHub.enabled"),
        dto.getBaseUrl(),
        requiredMillis(
            dto.getConnectTimeoutMillis(), "integrations.openCliHub.connectTimeoutMillis"),
        requiredMillis(
            dto.getRequestTimeoutMillis(), "integrations.openCliHub.requestTimeoutMillis"),
        requiredMillis(
            dto.getLongPollTimeoutMillis(), "integrations.openCliHub.longPollTimeoutMillis"),
        requiredInt(dto.getStreamBufferBytes(), "integrations.openCliHub.streamBufferBytes"),
        requiredInt(dto.getMaxJsonResponseBytes(), "integrations.openCliHub.maxJsonResponseBytes"),
        requiredInt(
            dto.getMaxErrorResponseBytes(), "integrations.openCliHub.maxErrorResponseBytes"),
        requiredInt(dto.getMaxOutputChars(), "integrations.openCliHub.maxOutputChars"));
  }

  private static SystemSettings.Seedance toSeedance(SystemSettingsIntegrationsDTO.SeedanceDTO dto) {
    return new SystemSettings.Seedance(
        requiredBoolean(dto.getEnabled(), "integrations.seedance.enabled"),
        dto.getWorkspaceId(),
        requiredInt(dto.getRetry(), "integrations.seedance.retry"),
        requiredMillis(
            dto.getHubExecutionTimeoutMillis(), "integrations.seedance.hubExecutionTimeoutMillis"),
        requiredMillis(
            dto.getStatusPollIntervalMillis(), "integrations.seedance.statusPollIntervalMillis"),
        requiredMillis(dto.getMaxWaitMillis(), "integrations.seedance.maxWaitMillis"));
  }

  private static SystemSettings.GptImage2 toGptImage2(
      SystemSettingsIntegrationsDTO.GptImage2DTO dto) {
    return new SystemSettings.GptImage2(
        requiredBoolean(dto.getPaidEnabled(), "integrations.gptImage2.paidEnabled"),
        requiredInt(dto.getAskTimeoutSeconds(), "integrations.gptImage2.askTimeoutSeconds"),
        requiredMillis(
            dto.getHubExecutionTimeoutMillis(), "integrations.gptImage2.hubExecutionTimeoutMillis"),
        requiredMillis(dto.getMaxWaitMillis(), "integrations.gptImage2.maxWaitMillis"));
  }

  private static SystemSettings.MiniMaxH3 toMiniMaxH3(
      SystemSettingsIntegrationsDTO.MiniMaxH3DTO dto) {
    return new SystemSettings.MiniMaxH3(
        requiredBoolean(dto.getEnabled(), "integrations.minimaxH3.enabled"),
        dto.getPromptAgentName(),
        requiredMillis(dto.getPromptMaxWaitMillis(), "integrations.minimaxH3.promptMaxWaitMillis"),
        dto.getComfyBaseUrl(),
        requiredMillis(
            dto.getComfyConnectTimeoutMillis(), "integrations.minimaxH3.comfyConnectTimeoutMillis"),
        requiredMillis(
            dto.getComfyRequestTimeoutMillis(), "integrations.minimaxH3.comfyRequestTimeoutMillis"),
        requiredMillis(
            dto.getComfyPollIntervalMillis(), "integrations.minimaxH3.comfyPollIntervalMillis"),
        requiredMillis(dto.getComfyMaxWaitMillis(), "integrations.minimaxH3.comfyMaxWaitMillis"));
  }

  private static SystemSettings.StorageMedia toStorageMedia(SystemSettingsStorageMediaDTO dto) {
    if (dto == null) {
      throw new IllegalArgumentException("storageMedia is required");
    }
    return new SystemSettings.StorageMedia(
        requiredMillis(dto.getUploadExpiresSeconds(), "storageMedia.uploadExpiresSeconds"),
        requiredBoolean(dto.getS3Enabled(), "storageMedia.s3Enabled"),
        requiredMillis(
            dto.getS3PresignDefaultExpiresSeconds(), "storageMedia.s3PresignDefaultExpiresSeconds"),
        requiredMillis(
            dto.getS3PresignMaxExpiresSeconds(), "storageMedia.s3PresignMaxExpiresSeconds"),
        requiredMillis(
            dto.getCanvasMediaProcessTimeoutMillis(),
            "storageMedia.canvasMediaProcessTimeoutMillis"),
        requiredInt(dto.getThumbnailMaxDimension(), "storageMedia.thumbnailMaxDimension"),
        requiredInt(dto.getThumbnailQuality(), "storageMedia.thumbnailQuality"));
  }

  private static SystemSettings.Advanced toAdvanced(SystemSettingsAdvancedDTO dto) {
    if (dto == null) {
      throw new IllegalArgumentException("advanced is required");
    }
    return new SystemSettings.Advanced(
        requiredMillis(dto.getResourceMaxBytes(), "advanced.resourceMaxBytes"),
        requiredMillis(
            dto.getProcessorLeaseDurationMillis(), "advanced.processorLeaseDurationMillis"),
        requiredMillis(
            dto.getProcessorHeartbeatIntervalMillis(), "advanced.processorHeartbeatIntervalMillis"),
        requiredMillis(
            dto.getThreadResolveFailureDelayMillis(), "advanced.threadResolveFailureDelayMillis"),
        requiredMillis(
            dto.getModelDispatchBusyFallbackDelayMillis(),
            "advanced.modelDispatchBusyFallbackDelayMillis"),
        requiredMillis(
            dto.getToolPreflightFailureDelayMillis(), "advanced.toolPreflightFailureDelayMillis"),
        requiredMillis(
            dto.getToolDispatchBusyFallbackDelayMillis(),
            "advanced.toolDispatchBusyFallbackDelayMillis"),
        requiredMillis(
            dto.getDispatcherLeaseDurationMillis(), "advanced.dispatcherLeaseDurationMillis"),
        requiredMillis(
            dto.getDispatcherPollIntervalMillis(), "advanced.dispatcherPollIntervalMillis"),
        requiredMillis(
            dto.getDispatcherRejectionDelayMillis(), "advanced.dispatcherRejectionDelayMillis"),
        requiredInt(dto.getDispatcherMaxDispatchTasks(), "advanced.dispatcherMaxDispatchTasks"),
        requiredInt(dto.getDispatcherWorkerConcurrency(), "advanced.dispatcherWorkerConcurrency"),
        requiredInt(
            dto.getDispatcherWorkerQueueCapacity(), "advanced.dispatcherWorkerQueueCapacity"),
        requiredInt(
            dto.getApplicationEventQueueCapacity(), "advanced.applicationEventQueueCapacity"),
        requiredMillis(dto.getApplicationEventMaxBytes(), "advanced.applicationEventMaxBytes"),
        requiredMillis(
            dto.getApplicationEventSendTimeoutMillis(),
            "advanced.applicationEventSendTimeoutMillis"),
        requiredMillis(
            dto.getApplicationEventHeartbeatIntervalMillis(),
            "advanced.applicationEventHeartbeatIntervalMillis"),
        requiredMillis(
            dto.getPostgresqlWorkNotificationPollMillis(),
            "advanced.postgresqlWorkNotificationPollMillis"),
        requiredMillis(
            dto.getPostgresqlWorkReconnectBackoffMillis(),
            "advanced.postgresqlWorkReconnectBackoffMillis"));
  }

  private static Map<String, List<PermissionRule>> toPermission(
      Map<String, List<SystemSettingsToolDTO.PermissionRuleDTO>> dto) {
    if (dto == null) {
      throw new IllegalArgumentException("tool.permission is required");
    }
    Map<String, List<PermissionRule>> result = new LinkedHashMap<>();
    dto.forEach(
        (toolId, rules) -> {
          PermissionKeyValidator.requireValid(toolId, "tool.permission key");
          if (rules == null) {
            throw new IllegalArgumentException("tool.permission." + toolId + " is required");
          }
          List<PermissionRule> converted = new ArrayList<>();
          for (SystemSettingsToolDTO.PermissionRuleDTO rule : rules) {
            if (rule == null) {
              throw new IllegalArgumentException(
                  "tool.permission." + toolId + " rule must not be null");
            }
            converted.add(
                new PermissionRule(
                    requiredText(rule.getPattern(), "tool.permission." + toolId + " pattern"),
                    PermissionAction.fromValue(
                        requiredText(rule.getAction(), "tool.permission." + toolId + " action"))));
          }
          result.put(toolId, converted);
        });
    return result;
  }

  private static SystemSettingsToolDTO fromTool(SystemSettings.Tool tool) {
    SystemSettingsToolDTO dto = new SystemSettingsToolDTO();
    Map<String, List<SystemSettingsToolDTO.PermissionRuleDTO>> permission = new LinkedHashMap<>();
    tool.permission()
        .forEach(
            (toolId, rules) -> {
              List<SystemSettingsToolDTO.PermissionRuleDTO> ruleDtos = new ArrayList<>();
              for (PermissionRule rule : rules) {
                SystemSettingsToolDTO.PermissionRuleDTO ruleDto =
                    new SystemSettingsToolDTO.PermissionRuleDTO();
                ruleDto.setPattern(rule.pattern());
                ruleDto.setAction(rule.action().value());
                ruleDtos.add(ruleDto);
              }
              permission.put(toolId, ruleDtos);
            });
    dto.setPermission(permission);
    dto.setDefaultYolo(tool.defaultYolo());
    dto.setModelGatewayBusyRetryMillis(tool.modelGatewayBusyRetryMillis());
    dto.setToolGatewayBusyRetryMillis(tool.toolGatewayBusyRetryMillis());
    dto.setToolGatewayOverloadRetryMillis(tool.toolGatewayOverloadRetryMillis());
    dto.setSkillLoadTimeoutMillis(tool.skillLoadTimeoutMillis());
    return dto;
  }

  private static SystemSettingsAiRuntimeDTO fromAiRuntime(SystemSettings.AiRuntime aiRuntime) {
    SystemSettingsAiRuntimeDTO dto = new SystemSettingsAiRuntimeDTO();
    dto.setRetryMaxRetries(aiRuntime.retryMaxRetries());
    dto.setRetryBackoffStrategy(aiRuntime.retryBackoffStrategy().name());
    dto.setRetryBaseDelayMillis(aiRuntime.retryBaseDelayMillis());
    dto.setRetryMaxDelayMillis(aiRuntime.retryMaxDelayMillis());
    dto.setCompactionKeepRecentTokens(aiRuntime.compactionKeepRecentTokens());
    dto.setCompactionFallbackModel(fromModelSelection(aiRuntime.compactionFallbackModel()));
    dto.setSubagentMaxDepth(aiRuntime.subagentMaxDepth());
    dto.setSubagentMaxConcurrency(aiRuntime.subagentMaxConcurrency());
    dto.setSubagentMaxTotalConcurrency(aiRuntime.subagentMaxTotalConcurrency());
    dto.setSubagentIdleTimeoutMillis(aiRuntime.subagentIdleTimeoutMillis());
    dto.setSubagentMaxTurns(aiRuntime.subagentMaxTurns());
    return dto;
  }

  private static SystemSettingsEnvironmentDTO fromEnvironment(
      SystemSettings.Environment environment) {
    SystemSettingsEnvironmentDTO dto = new SystemSettingsEnvironmentDTO();
    dto.setMaxResourceBytes(environment.maxResourceBytes());
    dto.setHeartbeatTimeoutMillis(environment.heartbeatTimeoutMillis());
    dto.setDirectoryListTimeoutMillis(environment.directoryListTimeoutMillis());
    return dto;
  }

  private static ModelSelection toModelSelection(HarnessModelSelectionDTO dto) {
    if (dto == null) {
      return null;
    }
    return new ModelSelection(dto.getProviderName(), dto.getModelName(), dto.getVariant());
  }

  private static HarnessModelSelectionDTO fromModelSelection(ModelSelection selection) {
    if (selection == null) {
      return null;
    }
    HarnessModelSelectionDTO dto = new HarnessModelSelectionDTO();
    dto.setProviderName(selection.providerName());
    dto.setModelName(selection.modelName());
    dto.setVariant(selection.variant());
    return dto;
  }

  private static SystemSettingsIntegrationsDTO fromIntegrations(
      SystemSettings.Integrations integrations) {
    SystemSettingsIntegrationsDTO dto = new SystemSettingsIntegrationsDTO();
    dto.setComfyui(fromComfyui(integrations.comfyui()));
    dto.setOpenCliHub(fromOpenCliHub(integrations.openCliHub()));
    dto.setSeedance(fromSeedance(integrations.seedance()));
    dto.setGptImage2(fromGptImage2(integrations.gptImage2()));
    dto.setMinimaxH3(fromMiniMaxH3(integrations.minimaxH3()));
    return dto;
  }

  private static SystemSettingsIntegrationsDTO.ComfyuiDTO fromComfyui(
      SystemSettings.Comfyui comfyui) {
    SystemSettingsIntegrationsDTO.ComfyuiDTO dto = new SystemSettingsIntegrationsDTO.ComfyuiDTO();
    dto.setEnabled(comfyui.enabled());
    dto.setBaseUrl(comfyui.baseUrl());
    dto.setConnectTimeoutMillis(comfyui.connectTimeoutMillis());
    dto.setReadTimeoutMillis(comfyui.readTimeoutMillis());
    dto.setWebsocketTimeoutMillis(comfyui.websocketTimeoutMillis());
    dto.setMaxInputFileBytes(comfyui.maxInputFileBytes());
    return dto;
  }

  private static SystemSettingsIntegrationsDTO.OpenCliHubDTO fromOpenCliHub(
      SystemSettings.OpenCliHub openCliHub) {
    SystemSettingsIntegrationsDTO.OpenCliHubDTO dto =
        new SystemSettingsIntegrationsDTO.OpenCliHubDTO();
    dto.setEnabled(openCliHub.enabled());
    dto.setBaseUrl(openCliHub.baseUrl());
    dto.setConnectTimeoutMillis(openCliHub.connectTimeoutMillis());
    dto.setRequestTimeoutMillis(openCliHub.requestTimeoutMillis());
    dto.setLongPollTimeoutMillis(openCliHub.longPollTimeoutMillis());
    dto.setStreamBufferBytes(openCliHub.streamBufferBytes());
    dto.setMaxJsonResponseBytes(openCliHub.maxJsonResponseBytes());
    dto.setMaxErrorResponseBytes(openCliHub.maxErrorResponseBytes());
    dto.setMaxOutputChars(openCliHub.maxOutputChars());
    return dto;
  }

  private static SystemSettingsIntegrationsDTO.SeedanceDTO fromSeedance(
      SystemSettings.Seedance seedance) {
    SystemSettingsIntegrationsDTO.SeedanceDTO dto = new SystemSettingsIntegrationsDTO.SeedanceDTO();
    dto.setEnabled(seedance.enabled());
    dto.setWorkspaceId(seedance.workspaceId());
    dto.setRetry(seedance.retry());
    dto.setHubExecutionTimeoutMillis(seedance.hubExecutionTimeoutMillis());
    dto.setStatusPollIntervalMillis(seedance.statusPollIntervalMillis());
    dto.setMaxWaitMillis(seedance.maxWaitMillis());
    return dto;
  }

  private static SystemSettingsIntegrationsDTO.GptImage2DTO fromGptImage2(
      SystemSettings.GptImage2 gptImage2) {
    SystemSettingsIntegrationsDTO.GptImage2DTO dto =
        new SystemSettingsIntegrationsDTO.GptImage2DTO();
    dto.setPaidEnabled(gptImage2.paidEnabled());
    dto.setAskTimeoutSeconds(gptImage2.askTimeoutSeconds());
    dto.setHubExecutionTimeoutMillis(gptImage2.hubExecutionTimeoutMillis());
    dto.setMaxWaitMillis(gptImage2.maxWaitMillis());
    return dto;
  }

  private static SystemSettingsIntegrationsDTO.MiniMaxH3DTO fromMiniMaxH3(
      SystemSettings.MiniMaxH3 minimaxH3) {
    SystemSettingsIntegrationsDTO.MiniMaxH3DTO dto =
        new SystemSettingsIntegrationsDTO.MiniMaxH3DTO();
    dto.setEnabled(minimaxH3.enabled());
    dto.setPromptAgentName(minimaxH3.promptAgentName());
    dto.setPromptMaxWaitMillis(minimaxH3.promptMaxWaitMillis());
    dto.setComfyBaseUrl(minimaxH3.comfyBaseUrl());
    dto.setComfyConnectTimeoutMillis(minimaxH3.comfyConnectTimeoutMillis());
    dto.setComfyRequestTimeoutMillis(minimaxH3.comfyRequestTimeoutMillis());
    dto.setComfyPollIntervalMillis(minimaxH3.comfyPollIntervalMillis());
    dto.setComfyMaxWaitMillis(minimaxH3.comfyMaxWaitMillis());
    return dto;
  }

  private static SystemSettingsStorageMediaDTO fromStorageMedia(
      SystemSettings.StorageMedia storageMedia) {
    SystemSettingsStorageMediaDTO dto = new SystemSettingsStorageMediaDTO();
    dto.setUploadExpiresSeconds(storageMedia.uploadExpiresSeconds());
    dto.setS3Enabled(storageMedia.s3Enabled());
    dto.setS3PresignDefaultExpiresSeconds(storageMedia.s3PresignDefaultExpiresSeconds());
    dto.setS3PresignMaxExpiresSeconds(storageMedia.s3PresignMaxExpiresSeconds());
    dto.setCanvasMediaProcessTimeoutMillis(storageMedia.canvasMediaProcessTimeoutMillis());
    dto.setThumbnailMaxDimension(storageMedia.thumbnailMaxDimension());
    dto.setThumbnailQuality(storageMedia.thumbnailQuality());
    return dto;
  }

  private static SystemSettingsAdvancedDTO fromAdvanced(SystemSettings.Advanced advanced) {
    SystemSettingsAdvancedDTO dto = new SystemSettingsAdvancedDTO();
    dto.setResourceMaxBytes(advanced.resourceMaxBytes());
    dto.setProcessorLeaseDurationMillis(advanced.processorLeaseDurationMillis());
    dto.setProcessorHeartbeatIntervalMillis(advanced.processorHeartbeatIntervalMillis());
    dto.setThreadResolveFailureDelayMillis(advanced.threadResolveFailureDelayMillis());
    dto.setModelDispatchBusyFallbackDelayMillis(advanced.modelDispatchBusyFallbackDelayMillis());
    dto.setToolPreflightFailureDelayMillis(advanced.toolPreflightFailureDelayMillis());
    dto.setToolDispatchBusyFallbackDelayMillis(advanced.toolDispatchBusyFallbackDelayMillis());
    dto.setDispatcherLeaseDurationMillis(advanced.dispatcherLeaseDurationMillis());
    dto.setDispatcherPollIntervalMillis(advanced.dispatcherPollIntervalMillis());
    dto.setDispatcherRejectionDelayMillis(advanced.dispatcherRejectionDelayMillis());
    dto.setDispatcherMaxDispatchTasks(advanced.dispatcherMaxDispatchTasks());
    dto.setDispatcherWorkerConcurrency(advanced.dispatcherWorkerConcurrency());
    dto.setDispatcherWorkerQueueCapacity(advanced.dispatcherWorkerQueueCapacity());
    dto.setApplicationEventQueueCapacity(advanced.applicationEventQueueCapacity());
    dto.setApplicationEventMaxBytes(advanced.applicationEventMaxBytes());
    dto.setApplicationEventSendTimeoutMillis(advanced.applicationEventSendTimeoutMillis());
    dto.setApplicationEventHeartbeatIntervalMillis(
        advanced.applicationEventHeartbeatIntervalMillis());
    dto.setPostgresqlWorkNotificationPollMillis(advanced.postgresqlWorkNotificationPollMillis());
    dto.setPostgresqlWorkReconnectBackoffMillis(advanced.postgresqlWorkReconnectBackoffMillis());
    return dto;
  }

  private static boolean requiredBoolean(Boolean value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static long requiredMillis(Long value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static int requiredInt(Integer value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String requiredText(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String rootMessage(Throwable error) {
    Throwable root = rootCause(error);
    return root.getMessage() == null ? error.getMessage() : root.getMessage();
  }

  private static Throwable rootCause(Throwable error) {
    Throwable cursor = error;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    return cursor;
  }
}
