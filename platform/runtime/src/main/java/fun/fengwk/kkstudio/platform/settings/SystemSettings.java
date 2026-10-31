package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionKeyValidator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;

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
 * aiRuntime / environment / network / storageMedia / advanced）完整携带，任何一个 section 缺失都失败。所有校验都在
 * canonical constructor 中执行：字段范围与跨字段约束（例如 retry maxDelay&gt;=baseDelay、processor
 * heartbeat&lt;lease）。permission 规则数组保序；不依赖 JSON 对象键顺序。
 *
 * <p>插件（含 ComfyUI 与 MiniMax-H3）的运行参数不再属于设置合同：它们只有插件 Schema 与配置同步 plugins 一个来源。本聚合只承载可持久化的
 * 非敏感配置。DB/server 连接、filesystem root/workdir/temp/二进制路径、daemon token/identity、access/API key、bearer
 * token、OpenCLI instanceId 等秘密与 bootstrap 输入绝不进入本模型。
 */
public record SystemSettings(
    Tool tool,
    AiRuntime aiRuntime,
    Environment environment,
    Network network,
    StorageMedia storageMedia,
    Advanced advanced) {

  /** 安全默认聚合：与 {@code V1__schema.sql} 的默认行完全一致（由 codec 测试守护）。 */
  public static final SystemSettings DEFAULT =
      new SystemSettings(
          Tool.DEFAULT,
          AiRuntime.DEFAULT,
          Environment.DEFAULT,
          Network.DEFAULT,
          StorageMedia.DEFAULT,
          Advanced.DEFAULT);

  public SystemSettings {
    tool = Objects.requireNonNull(tool, "tool");
    aiRuntime = Objects.requireNonNull(aiRuntime, "aiRuntime");
    environment = Objects.requireNonNull(environment, "environment");
    network = Objects.requireNonNull(network, "network");
    storageMedia = Objects.requireNonNull(storageMedia, "storageMedia");
    advanced = Objects.requireNonNull(advanced, "advanced");
  }

  /** tool section：权限规则（保序数组）+ 模型/工具 Gateway 重试预算。 */
  public record Tool(
      Map<String, List<PermissionRule>> permission,
      long modelGatewayBusyRetryMillis,
      long toolGatewayBusyRetryMillis,
      long toolGatewayOverloadRetryMillis) {

    /** 默认 permission：write/edit/bash 各自 {@code * -> ask}（read 保持不限制）。 */
    public static final Tool DEFAULT =
        new Tool(
            Map.of(
                "write", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "edit", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "bash", List.of(new PermissionRule("*", PermissionAction.ASK))),
            5_000L,
            1_000L,
            5_000L);

    public Tool {
      permission = copyPermission(permission);
      SystemSettingsValidation.requirePositive(
          modelGatewayBusyRetryMillis, "tool.modelGatewayBusyRetryMillis");
      SystemSettingsValidation.requirePositive(
          toolGatewayBusyRetryMillis, "tool.toolGatewayBusyRetryMillis");
      SystemSettingsValidation.requirePositive(
          toolGatewayOverloadRetryMillis, "tool.toolGatewayOverloadRetryMillis");
    }

    private static Map<String, List<PermissionRule>> copyPermission(
        Map<String, List<PermissionRule>> source) {
      Objects.requireNonNull(source, "tool.permission");
      Map<String, List<PermissionRule>> copy = new LinkedHashMap<>();
      source.forEach(
          (toolName, rules) -> {
            PermissionKeyValidator.requireValid(toolName, "tool.permission key");
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

  /** aiRuntime section：共享调用重试、自动压缩保留量、subagent 预算与模型 HTTP 重试状态白名单。 */
  public record AiRuntime(
      int retryMaxRetries,
      InvocationRetryBackoffStrategy retryBackoffStrategy,
      long retryBaseDelayMillis,
      long retryMaxDelayMillis,
      int compactionKeepRecentTokens,
      int subagentMaxDepth,
      int subagentMaxConcurrency,
      int subagentMaxTotalConcurrency,
      int subagentMaxTurns,
      List<Integer> modelHttpRetryStatusCodes) {

    public static final AiRuntime DEFAULT =
        new AiRuntime(
            3,
            InvocationRetryBackoffStrategy.EXPONENTIAL,
            2_000L,
            60_000L,
            20_000,
            2,
            10,
            0,
            50,
            ModelHttpErrorPolicy.DEFAULT_RETRY_STATUS_CODES);

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
      SystemSettingsValidation.requireAtLeast(
          subagentMaxTotalConcurrency, 0, "aiRuntime.subagentMaxTotalConcurrency");
      SystemSettingsValidation.requireAtLeast(subagentMaxTurns, 1, "aiRuntime.subagentMaxTurns");
      // 白名单的唯一权威校验/规范化在 ModelHttpErrorPolicy：这里只复用，不再保留第二份 400–599 规则。
      modelHttpRetryStatusCodes =
          new ModelHttpErrorPolicy(
                  Objects.requireNonNull(
                      modelHttpRetryStatusCodes, "aiRuntime.modelHttpRetryStatusCodes"))
              .retryStatusCodes();
    }
  }

  /** environment section：daemon gateway 的资源边界与心跳超时。 */
  public record Environment(long maxResourceBytes, long heartbeatTimeoutMillis) {

    /** 默认单条/聚合资源字节预算：16 MiB，与 {@code advanced.resourceMaxBytes} 同一业务上限。 */
    public static final Environment DEFAULT = new Environment(16L * 1024 * 1024, 60_000L);

    public Environment {
      SystemSettingsValidation.requirePositiveMillis(
          maxResourceBytes, "environment.maxResourceBytes");
      SystemSettingsValidation.requirePositiveMillis(
          heartbeatTimeoutMillis, "environment.heartbeatTimeoutMillis");
    }
  }

  /** network section：Backend 唯一全局 HTTP 代理，保存后新发起请求生效；null 地址明确直连，不读取宿主代理。 */
  public record Network(String proxyUrl, String noProxyHosts) {

    public static final Network DEFAULT = new Network(null, "localhost,127.*,::1");

    public Network {
      if (proxyUrl != null && proxyUrl.length() > 2048) {
        throw new IllegalArgumentException("network.proxyUrl must not exceed 2048 characters");
      }
      if (noProxyHosts == null) {
        throw new IllegalArgumentException("network.noProxyHosts is required");
      }
      if (noProxyHosts.length() > 4096) {
        throw new IllegalArgumentException("network.noProxyHosts must not exceed 4096 characters");
      }
      // 分步委托同一公共解析器，给出精确字段错误；绝不传播可能携带凭据的解析消息或 cause。
      try {
        HttpProxySelector.fixed(proxyUrl, "");
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("network.proxyUrl is invalid");
      }
      try {
        HttpProxySelector.fixed(proxyUrl, noProxyHosts);
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("network.noProxyHosts is invalid");
      }
    }
  }

  /** storageMedia section：上传/S3 预签名、Canvas 媒体处理与受控临时资源的非敏感预算。 */
  public record StorageMedia(
      long uploadExpiresSeconds,
      long s3PresignDefaultExpiresSeconds,
      long s3PresignMaxExpiresSeconds,
      long canvasMediaProcessTimeoutMillis,
      int thumbnailMaxDimension,
      int thumbnailQuality,
      long temporaryResourceTtlSeconds,
      long temporaryResourceCleanupIntervalSeconds) {

    public static final StorageMedia DEFAULT =
        new StorageMedia(86_400L, 1_800L, 3_600L, 30_000L, 512, 80, 259_200L, 1_800L);

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
      SystemSettingsValidation.requirePositive(
          temporaryResourceTtlSeconds, "storageMedia.temporaryResourceTtlSeconds");
      SystemSettingsValidation.requirePositive(
          temporaryResourceCleanupIntervalSeconds,
          "storageMedia.temporaryResourceCleanupIntervalSeconds");
    }
  }

  /** advanced section：processor/executor/事件通道/工作通知的进程级运行预算。 */
  public record Advanced(
      long resourceMaxBytes,
      long processorLeaseDurationMillis,
      long processorHeartbeatIntervalMillis,
      long threadResolveFailureDelayMillis,
      long modelDispatchBusyFallbackDelayMillis,
      long toolPreflightFailureDelayMillis,
      long toolDispatchBusyFallbackDelayMillis,
      int applicationEventQueueCapacity,
      long applicationEventMaxBytes,
      long applicationEventSendTimeoutMillis,
      long applicationEventHeartbeatIntervalMillis,
      long postgresqlWorkNotificationPollMillis,
      long postgresqlWorkReconnectBackoffMillis) {

    public static final Advanced DEFAULT =
        new Advanced(
            16L * 1024 * 1024,
            30_000L,
            10_000L,
            1_000L,
            1_000L,
            1_000L,
            1_000L,
            512,
            2L * 1024 * 1024,
            10_000L,
            20_000L,
            5_000L,
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
    }
  }
}
