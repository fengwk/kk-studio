package fun.fengwk.kkstudio.core.systemsettings;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 全局 system settings 的强类型聚合。
 *
 * <p>这是 {@code system_setting} 单行（{@code id=1}）中 {@code config} JSON 的唯一语义模型：六个必填 section （tool /
 * aiRuntime / environment / integrations / storageMedia / advanced）完整携带，任何一个 section 缺失都 失败。所有校验都在
 * canonical constructor 中执行：字段范围、跨字段约束（例如 retry maxDelay&gt;=baseDelay、processor
 * heartbeat&lt;lease、comfyPollInterval&lt;comfyMaxWait）以及「启用集成必须携带其身份 / 位置字段」。permission 规则数组保序；不依赖
 * JSON 对象键顺序。
 *
 * <p>安全边界：本聚合只承载可持久化的非敏感配置。DB/Redis/server 连接、filesystem root/workdir/temp/二进制路径、 daemon
 * token/identity、access/API key、bearer token、OpenCLI instanceId 等秘密与 bootstrap 输入绝不进入本模型。
 */
public record SystemSettings(
    Tool tool,
    AiRuntime aiRuntime,
    Environment environment,
    Integrations integrations,
    StorageMedia storageMedia,
    Advanced advanced) {

  /** 安全默认聚合：与 {@code V1__schema.sql} 的默认行完全一致（由 codec 测试守护）。 */
  public static final SystemSettings DEFAULT =
      new SystemSettings(
          Tool.DEFAULT,
          AiRuntime.DEFAULT,
          Environment.DEFAULT,
          Integrations.DEFAULT,
          StorageMedia.DEFAULT,
          Advanced.DEFAULT);

  public SystemSettings {
    tool = Objects.requireNonNull(tool, "tool");
    aiRuntime = Objects.requireNonNull(aiRuntime, "aiRuntime");
    environment = Objects.requireNonNull(environment, "environment");
    integrations = Objects.requireNonNull(integrations, "integrations");
    storageMedia = Objects.requireNonNull(storageMedia, "storageMedia");
    advanced = Objects.requireNonNull(advanced, "advanced");
  }

  /** tool section：权限规则（保序数组）+ 默认 YOLO + 模型/工具 Gateway 与 skill 加载预算。 */
  public record Tool(
      Map<String, List<PermissionRule>> permission,
      boolean defaultYolo,
      long modelGatewayBusyRetryMillis,
      long toolGatewayBusyRetryMillis,
      long toolGatewayOverloadRetryMillis,
      long skillLoadTimeoutMillis) {

    /** 默认 permission：write/edit/bash 各自 {@code * -> ask}（与 V1 默认行一致；read 保持不限制）。 */
    public static final Tool DEFAULT =
        new Tool(
            Map.of(
                "write", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "edit", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "bash", List.of(new PermissionRule("*", PermissionAction.ASK))),
            false,
            5_000L,
            1_000L,
            5_000L,
            30_000L);

    public Tool {
      permission = copyPermission(permission);
      SystemSettingsValidation.requirePositive(
          modelGatewayBusyRetryMillis, "tool.modelGatewayBusyRetryMillis");
      SystemSettingsValidation.requirePositive(
          toolGatewayBusyRetryMillis, "tool.toolGatewayBusyRetryMillis");
      SystemSettingsValidation.requirePositive(
          toolGatewayOverloadRetryMillis, "tool.toolGatewayOverloadRetryMillis");
      SystemSettingsValidation.requirePositive(
          skillLoadTimeoutMillis, "tool.skillLoadTimeoutMillis");
    }

    private static Map<String, List<PermissionRule>> copyPermission(
        Map<String, List<PermissionRule>> source) {
      Objects.requireNonNull(source, "tool.permission");
      Map<String, List<PermissionRule>> copy = new LinkedHashMap<>();
      source.forEach(
          (toolName, rules) -> {
            if (toolName == null || toolName.isBlank()) {
              throw new IllegalArgumentException("tool.permission tool name must not be blank");
            }
            if (!toolName.equals(toolName.strip())) {
              throw new IllegalArgumentException(
                  "tool.permission tool name must not contain surrounding whitespace");
            }
            List<PermissionRule> copiedRules = new ArrayList<>();
            for (PermissionRule rule : Objects.requireNonNull(rules, "tool.permission rules")) {
              if (rule == null) {
                throw new IllegalArgumentException("tool.permission rule must not be null");
              }
              SystemSettingsValidation.requireValidPattern(rule.pattern());
              copiedRules.add(rule);
            }
            copy.put(toolName, List.copyOf(copiedRules));
          });
      return Collections.unmodifiableMap(copy);
    }
  }

  /** aiRuntime section：共享调用重试、自动压缩 fallback、subagent 预算。 */
  public record AiRuntime(
      int retryMaxRetries,
      InvocationRetryBackoffStrategy retryBackoffStrategy,
      long retryBaseDelayMillis,
      long retryMaxDelayMillis,
      int compactionKeepRecentTokens,
      ModelSelection compactionFallbackModel,
      int subagentMaxDepth,
      int subagentMaxConcurrency,
      Integer subagentMaxTotalConcurrency,
      long subagentIdleTimeoutMillis,
      int subagentMaxTurns) {

    public static final AiRuntime DEFAULT =
        new AiRuntime(
            3,
            InvocationRetryBackoffStrategy.EXPONENTIAL,
            2_000L,
            60_000L,
            20_000,
            null,
            2,
            10,
            null,
            0L,
            50);

    public AiRuntime {
      SystemSettingsValidation.requireNonNegativeInt(retryMaxRetries, "aiRuntime.retryMaxRetries");
      retryBackoffStrategy =
          Objects.requireNonNull(retryBackoffStrategy, "aiRuntime.retryBackoffStrategy");
      SystemSettingsValidation.requirePositiveMillis(
          retryBaseDelayMillis, "aiRuntime.retryBaseDelayMillis");
      SystemSettingsValidation.requirePositiveMillis(
          retryMaxDelayMillis, "aiRuntime.retryMaxDelayMillis");
      if (retryMaxDelayMillis < retryBaseDelayMillis) {
        throw new IllegalArgumentException(
            "aiRuntime.retryMaxDelayMillis must not be less than retryBaseDelayMillis");
      }
      SystemSettingsValidation.requirePositive(
          compactionKeepRecentTokens, "aiRuntime.compactionKeepRecentTokens");
      SystemSettingsValidation.requireAtLeast(subagentMaxDepth, 1, "aiRuntime.subagentMaxDepth");
      SystemSettingsValidation.requireAtLeast(
          subagentMaxConcurrency, 1, "aiRuntime.subagentMaxConcurrency");
      if (subagentMaxTotalConcurrency != null) {
        SystemSettingsValidation.requireAtLeast(
            subagentMaxTotalConcurrency, 1, "aiRuntime.subagentMaxTotalConcurrency");
      }
      SystemSettingsValidation.requireNonNegativeMillis(
          subagentIdleTimeoutMillis, "aiRuntime.subagentIdleTimeoutMillis");
      SystemSettingsValidation.requireAtLeast(subagentMaxTurns, 1, "aiRuntime.subagentMaxTurns");
    }
  }

  /** environment section：daemon gateway 的资源/消息边界与超时。 */
  public record Environment(
      long maxResourceBytes,
      long maxMessageBytes,
      long heartbeatTimeoutMillis,
      long directoryListTimeoutMillis) {

    public static final Environment DEFAULT =
        new Environment(8L * 1024 * 1024, 16L * 1024 * 1024, 60_000L, 10_000L);

    public Environment {
      SystemSettingsValidation.requirePositiveMillis(
          maxResourceBytes, "environment.maxResourceBytes");
      SystemSettingsValidation.requirePositiveMillis(
          maxMessageBytes, "environment.maxMessageBytes");
      SystemSettingsValidation.requirePositiveMillis(
          heartbeatTimeoutMillis, "environment.heartbeatTimeoutMillis");
      SystemSettingsValidation.requirePositiveMillis(
          directoryListTimeoutMillis, "environment.directoryListTimeoutMillis");
    }
  }

  /** integrations section：外部媒体/生成集成的非敏感运行参数，每个集成都是必填嵌套对象。 */
  public record Integrations(
      Comfyui comfyui,
      OpenCliHub openCliHub,
      Seedance seedance,
      GptImage2 gptImage2,
      MiniMaxH3 minimaxH3) {

    public static final Integrations DEFAULT =
        new Integrations(
            Comfyui.DEFAULT,
            OpenCliHub.DEFAULT,
            Seedance.DEFAULT,
            GptImage2.DEFAULT,
            MiniMaxH3.DEFAULT);

    public Integrations {
      comfyui = Objects.requireNonNull(comfyui, "integrations.comfyui");
      openCliHub = Objects.requireNonNull(openCliHub, "integrations.openCliHub");
      seedance = Objects.requireNonNull(seedance, "integrations.seedance");
      gptImage2 = Objects.requireNonNull(gptImage2, "integrations.gptImage2");
      minimaxH3 = Objects.requireNonNull(minimaxH3, "integrations.minimaxH3");
    }
  }

  /** {@code integrations.comfyui}：ComfyUI 运行端点与文件输入预算。apiKey 属秘密，不进入聚合。 */
  public record Comfyui(
      boolean enabled,
      String baseUrl,
      long connectTimeoutMillis,
      long readTimeoutMillis,
      long websocketTimeoutMillis,
      long maxInputFileBytes) {

    public static final Comfyui DEFAULT =
        new Comfyui(false, null, 10_000L, 30_000L, 1_800_000L, 50L * 1024 * 1024);

    public Comfyui {
      SystemSettingsValidation.requirePositiveMillis(
          connectTimeoutMillis, "integrations.comfyui.connectTimeoutMillis");
      SystemSettingsValidation.requirePositiveMillis(
          readTimeoutMillis, "integrations.comfyui.readTimeoutMillis");
      SystemSettingsValidation.requirePositiveMillis(
          websocketTimeoutMillis, "integrations.comfyui.websocketTimeoutMillis");
      SystemSettingsValidation.requirePositiveMillis(
          maxInputFileBytes, "integrations.comfyui.maxInputFileBytes");
      baseUrl =
          SystemSettingsValidation.requirePresentableUrl(baseUrl, "integrations.comfyui.baseUrl");
      if (enabled && baseUrl == null) {
        throw new IllegalArgumentException(
            "integrations.comfyui.baseUrl is required when comfyui is enabled");
      }
    }
  }

  /** {@code integrations.openCliHub}：OpenCLI Hub 的无凭证共享配置。instanceId 属部署身份，不进入聚合。 */
  public record OpenCliHub(
      boolean enabled,
      String baseUrl,
      long connectTimeoutMillis,
      long requestTimeoutMillis,
      long longPollTimeoutMillis,
      int streamBufferBytes,
      int maxJsonResponseBytes,
      int maxErrorResponseBytes,
      int maxOutputChars) {

    public static final OpenCliHub DEFAULT =
        new OpenCliHub(
            false,
            "http://vps-opencli-hub:8080",
            5_000L,
            120_000L,
            130_000L,
            16 * 1024,
            512 * 1024,
            4096,
            65_535);

    public OpenCliHub {
      baseUrl =
          SystemSettingsValidation.requireHttpOrigin(baseUrl, "integrations.openCliHub.baseUrl");
      if (enabled && baseUrl == null) {
        throw new IllegalArgumentException(
            "integrations.openCliHub.baseUrl is required when openCliHub is enabled");
      }
      SystemSettingsValidation.requireBounded(
          connectTimeoutMillis, 1L, 2L * 60 * 1000, "integrations.openCliHub.connectTimeoutMillis");
      SystemSettingsValidation.requireBounded(
          requestTimeoutMillis,
          1_000L,
          30L * 60 * 1000,
          "integrations.openCliHub.requestTimeoutMillis");
      SystemSettingsValidation.requireBounded(
          longPollTimeoutMillis,
          121_000L,
          10L * 60 * 1000,
          "integrations.openCliHub.longPollTimeoutMillis");
      SystemSettingsValidation.requireBounded(
          streamBufferBytes, 1024, 1024 * 1024, "integrations.openCliHub.streamBufferBytes");
      SystemSettingsValidation.requireBounded(
          maxJsonResponseBytes,
          1024,
          4 * 1024 * 1024,
          "integrations.openCliHub.maxJsonResponseBytes");
      SystemSettingsValidation.requireBounded(
          maxErrorResponseBytes, 256, 64 * 1024, "integrations.openCliHub.maxErrorResponseBytes");
      SystemSettingsValidation.requireBounded(
          maxOutputChars, 1024, 1_000_000, "integrations.openCliHub.maxOutputChars");
    }
  }

  /** {@code integrations.seedance}：Seedance 真实提交开关、workspace 与有界轮询预算。 */
  public record Seedance(
      boolean enabled,
      String workspaceId,
      int retry,
      long hubExecutionTimeoutMillis,
      long statusPollIntervalMillis,
      long maxWaitMillis) {

    public static final Seedance DEFAULT =
        new Seedance(false, null, 0, 600_000L, 30_000L, 1_800_000L);

    public Seedance {
      workspaceId =
          SystemSettingsValidation.requireOptionalBoundedText(
              workspaceId, 256, "integrations.seedance.workspaceId");
      if (enabled && workspaceId == null) {
        throw new IllegalArgumentException(
            "integrations.seedance.workspaceId is required when seedance is enabled");
      }
      SystemSettingsValidation.requireBounded(retry, 0, 5, "integrations.seedance.retry");
      SystemSettingsValidation.requireBounded(
          hubExecutionTimeoutMillis,
          1_000L,
          30L * 60 * 1000,
          "integrations.seedance.hubExecutionTimeoutMillis");
      SystemSettingsValidation.requireBounded(
          statusPollIntervalMillis,
          1L,
          5L * 60 * 1000,
          "integrations.seedance.statusPollIntervalMillis");
      SystemSettingsValidation.requireBounded(
          maxWaitMillis, 1_000L, 4L * 60 * 60 * 1000, "integrations.seedance.maxWaitMillis");
      if (statusPollIntervalMillis >= maxWaitMillis) {
        throw new IllegalArgumentException(
            "integrations.seedance.statusPollIntervalMillis must be less than maxWaitMillis");
      }
    }
  }

  /** {@code integrations.gptImage2}：GPT Image 2 付费提交开关与有界执行预算。 */
  public record GptImage2(
      boolean paidEnabled,
      int askTimeoutSeconds,
      long hubExecutionTimeoutMillis,
      long maxWaitMillis) {

    public static final GptImage2 DEFAULT = new GptImage2(false, 900, 960_000L, 1_200_000L);

    public GptImage2 {
      SystemSettingsValidation.requireBounded(
          askTimeoutSeconds, 1, 1740, "integrations.gptImage2.askTimeoutSeconds");
      SystemSettingsValidation.requireBounded(
          hubExecutionTimeoutMillis,
          31_000L,
          30L * 60 * 1000,
          "integrations.gptImage2.hubExecutionTimeoutMillis");
      SystemSettingsValidation.requireBounded(
          maxWaitMillis, 1_000L, 2L * 60 * 60 * 1000, "integrations.gptImage2.maxWaitMillis");
      if (hubExecutionTimeoutMillis / 1000L <= askTimeoutSeconds + 30L) {
        throw new IllegalArgumentException(
            "integrations.gptImage2.hubExecutionTimeoutMillis must exceed askTimeoutSeconds plus"
                + " OpenCLI padding");
      }
    }
  }

  /** {@code integrations.minimaxH3}：MiniMax-H3 Ref2VA adapter 的非敏感配置。comfyBearerToken 属秘密，不进入聚合。 */
  public record MiniMaxH3(
      boolean enabled,
      String promptAgentName,
      String promptEnvironmentName,
      long promptMaxWaitMillis,
      String comfyBaseUrl,
      long comfyConnectTimeoutMillis,
      long comfyRequestTimeoutMillis,
      long comfyPollIntervalMillis,
      long comfyMaxWaitMillis) {

    public static final MiniMaxH3 DEFAULT =
        new MiniMaxH3(false, null, null, 600_000L, null, 10_000L, 30_000L, 2_000L, 1_800_000L);

    public MiniMaxH3 {
      SystemSettingsValidation.requirePositiveBounded(
          promptMaxWaitMillis, 30L * 60 * 1000, "integrations.minimaxH3.promptMaxWaitMillis");
      SystemSettingsValidation.requirePositiveBounded(
          comfyConnectTimeoutMillis,
          60L * 1000,
          "integrations.minimaxH3.comfyConnectTimeoutMillis");
      SystemSettingsValidation.requirePositiveBounded(
          comfyRequestTimeoutMillis,
          5L * 60 * 1000,
          "integrations.minimaxH3.comfyRequestTimeoutMillis");
      SystemSettingsValidation.requirePositiveBounded(
          comfyPollIntervalMillis, 60L * 1000, "integrations.minimaxH3.comfyPollIntervalMillis");
      SystemSettingsValidation.requirePositiveBounded(
          comfyMaxWaitMillis, 2L * 60 * 60 * 1000, "integrations.minimaxH3.comfyMaxWaitMillis");
      if (comfyPollIntervalMillis >= comfyMaxWaitMillis) {
        throw new IllegalArgumentException(
            "integrations.minimaxH3.comfyPollIntervalMillis must be less than comfyMaxWaitMillis");
      }
      promptAgentName =
          SystemSettingsValidation.requireOptionalAgentName(
              promptAgentName, "integrations.minimaxH3.promptAgentName");
      promptEnvironmentName =
          SystemSettingsValidation.requireOptionalEnvironmentName(
              promptEnvironmentName, "integrations.minimaxH3.promptEnvironmentName");
      comfyBaseUrl =
          SystemSettingsValidation.requirePresentableUrl(
              comfyBaseUrl, "integrations.minimaxH3.comfyBaseUrl");
      if (enabled) {
        if (promptAgentName == null) {
          throw new IllegalArgumentException(
              "integrations.minimaxH3.promptAgentName is required when minimaxH3 is enabled");
        }
        if (promptEnvironmentName == null) {
          throw new IllegalArgumentException(
              "integrations.minimaxH3.promptEnvironmentName is required when minimaxH3 is enabled");
        }
        if (comfyBaseUrl == null) {
          throw new IllegalArgumentException(
              "integrations.minimaxH3.comfyBaseUrl is required when minimaxH3 is enabled");
        }
      }
    }
  }

  /** storageMedia section：上传/S3 预签名与 Canvas 媒体处理的非敏感预算。 */
  public record StorageMedia(
      long uploadExpiresSeconds,
      boolean s3Enabled,
      long s3PresignDefaultExpiresSeconds,
      long s3PresignMaxExpiresSeconds,
      long canvasMediaProcessTimeoutMillis,
      int thumbnailMaxDimension,
      int thumbnailQuality) {

    public static final StorageMedia DEFAULT =
        new StorageMedia(3_600L, false, 600L, 3_600L, 30_000L, 512, 80);

    public StorageMedia {
      SystemSettingsValidation.requirePositive(
          uploadExpiresSeconds, "storageMedia.uploadExpiresSeconds");
      SystemSettingsValidation.requirePositive(
          s3PresignDefaultExpiresSeconds, "storageMedia.s3PresignDefaultExpiresSeconds");
      SystemSettingsValidation.requirePositive(
          s3PresignMaxExpiresSeconds, "storageMedia.s3PresignMaxExpiresSeconds");
      if (s3PresignDefaultExpiresSeconds > s3PresignMaxExpiresSeconds) {
        throw new IllegalArgumentException(
            "storageMedia.s3PresignDefaultExpiresSeconds must not exceed"
                + " s3PresignMaxExpiresSeconds");
      }
      SystemSettingsValidation.requirePositiveMillis(
          canvasMediaProcessTimeoutMillis, "storageMedia.canvasMediaProcessTimeoutMillis");
      SystemSettingsValidation.requirePositive(
          thumbnailMaxDimension, "storageMedia.thumbnailMaxDimension");
      SystemSettingsValidation.requireBounded(
          thumbnailQuality, 1, 100, "storageMedia.thumbnailQuality");
    }
  }

  /** advanced section：processor/dispatcher/executor/事件通道/工作通知的进程级运行预算。 */
  public record Advanced(
      long resourceMaxBytes,
      long processorLeaseDurationMillis,
      long processorHeartbeatIntervalMillis,
      long threadResolveFailureDelayMillis,
      long modelDispatchBusyFallbackDelayMillis,
      long toolPreflightFailureDelayMillis,
      long toolDispatchBusyFallbackDelayMillis,
      long dispatcherLeaseDurationMillis,
      long dispatcherPollIntervalMillis,
      long dispatcherRejectionDelayMillis,
      int dispatcherMaxDispatchTasks,
      int dispatcherWorkerConcurrency,
      int dispatcherWorkerQueueCapacity,
      long canvasRealtimeMaxLength,
      int canvasFunctionExecutorCoreSize,
      int canvasFunctionExecutorMaxSize,
      int canvasFunctionExecutorQueueCapacity,
      int applicationEventQueueCapacity,
      long applicationEventMaxBytes,
      long applicationEventSendTimeoutMillis,
      long applicationEventHeartbeatIntervalMillis,
      long postgresqlWorkNotificationPollMillis,
      long postgresqlWorkReconnectBackoffMillis,
      long redisRealtimeRetryDelayMillis) {

    public static final Advanced DEFAULT =
        new Advanced(
            16L * 1024 * 1024,
            30_000L,
            10_000L,
            1_000L,
            1_000L,
            1_000L,
            1_000L,
            30_000L,
            1_000L,
            1_000L,
            64,
            16,
            64,
            5_000L,
            2,
            4,
            64,
            512,
            2L * 1024 * 1024,
            10_000L,
            20_000L,
            5_000L,
            1_000L,
            1_000L);

    public Advanced {
      SystemSettingsValidation.requirePositiveMillis(resourceMaxBytes, "advanced.resourceMaxBytes");
      SystemSettingsValidation.requirePositiveMillis(
          processorLeaseDurationMillis, "advanced.processorLeaseDurationMillis");
      SystemSettingsValidation.requirePositiveMillis(
          processorHeartbeatIntervalMillis, "advanced.processorHeartbeatIntervalMillis");
      if (processorHeartbeatIntervalMillis >= processorLeaseDurationMillis) {
        throw new IllegalArgumentException(
            "advanced.processorHeartbeatIntervalMillis must be less than"
                + " processorLeaseDurationMillis");
      }
      SystemSettingsValidation.requirePositiveMillis(
          threadResolveFailureDelayMillis, "advanced.threadResolveFailureDelayMillis");
      SystemSettingsValidation.requirePositiveMillis(
          modelDispatchBusyFallbackDelayMillis, "advanced.modelDispatchBusyFallbackDelayMillis");
      SystemSettingsValidation.requirePositiveMillis(
          toolPreflightFailureDelayMillis, "advanced.toolPreflightFailureDelayMillis");
      SystemSettingsValidation.requirePositiveMillis(
          toolDispatchBusyFallbackDelayMillis, "advanced.toolDispatchBusyFallbackDelayMillis");
      SystemSettingsValidation.requirePositiveMillis(
          dispatcherLeaseDurationMillis, "advanced.dispatcherLeaseDurationMillis");
      SystemSettingsValidation.requirePositiveMillis(
          dispatcherPollIntervalMillis, "advanced.dispatcherPollIntervalMillis");
      SystemSettingsValidation.requirePositiveMillis(
          dispatcherRejectionDelayMillis, "advanced.dispatcherRejectionDelayMillis");
      SystemSettingsValidation.requireAtLeast(
          dispatcherMaxDispatchTasks, 1, "advanced.dispatcherMaxDispatchTasks");
      SystemSettingsValidation.requireAtLeast(
          dispatcherWorkerConcurrency, 1, "advanced.dispatcherWorkerConcurrency");
      SystemSettingsValidation.requireAtLeast(
          dispatcherWorkerQueueCapacity, 1, "advanced.dispatcherWorkerQueueCapacity");
      SystemSettingsValidation.requirePositiveMillis(
          canvasRealtimeMaxLength, "advanced.canvasRealtimeMaxLength");
      SystemSettingsValidation.requireAtLeast(
          canvasFunctionExecutorCoreSize, 1, "advanced.canvasFunctionExecutorCoreSize");
      if (canvasFunctionExecutorMaxSize < canvasFunctionExecutorCoreSize) {
        throw new IllegalArgumentException(
            "advanced.canvasFunctionExecutorMaxSize must not be less than"
                + " canvasFunctionExecutorCoreSize");
      }
      SystemSettingsValidation.requireAtLeast(
          canvasFunctionExecutorQueueCapacity, 1, "advanced.canvasFunctionExecutorQueueCapacity");
      SystemSettingsValidation.requireAtLeast(
          applicationEventQueueCapacity, 1, "advanced.applicationEventQueueCapacity");
      SystemSettingsValidation.requirePositiveMillis(
          applicationEventMaxBytes, "advanced.applicationEventMaxBytes");
      SystemSettingsValidation.requirePositiveMillis(
          applicationEventSendTimeoutMillis, "advanced.applicationEventSendTimeoutMillis");
      SystemSettingsValidation.requirePositiveMillis(
          applicationEventHeartbeatIntervalMillis,
          "advanced.applicationEventHeartbeatIntervalMillis");
      SystemSettingsValidation.requirePositiveMillis(
          postgresqlWorkNotificationPollMillis, "advanced.postgresqlWorkNotificationPollMillis");
      SystemSettingsValidation.requirePositiveMillis(
          postgresqlWorkReconnectBackoffMillis, "advanced.postgresqlWorkReconnectBackoffMillis");
      SystemSettingsValidation.requirePositiveMillis(
          redisRealtimeRetryDelayMillis, "advanced.redisRealtimeRetryDelayMillis");
    }
  }
}
