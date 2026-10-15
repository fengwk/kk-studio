package fun.fengwk.kkstudio.plugin.opencli;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * OpenCLI 插件私有配置值对象。
 *
 * <p>聚合 OpenCLI Hub 运行参数、GptImage2 任务预算与 Seedance 任务预算。默认值唯一来源是 {@link
 * OpenCliPlugin#CONFIGURATION_SCHEMA}。私有身份 {@code instanceId} 属于受保护秘密字段，不进入本模型。
 */
record OpenCliSettings(
    boolean enabled,
    String baseUrl,
    long connectTimeoutMillis,
    long requestTimeoutMillis,
    long longPollTimeoutMillis,
    int streamBufferBytes,
    int maxJsonResponseBytes,
    int maxErrorResponseBytes,
    int maxOutputChars,
    GptImage2 gptImage2,
    Seedance seedance) {

  private static final ObjectMapper STRICT_MAPPER =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  OpenCliSettings {
    baseUrl = OpenCliSettingsValidation.requireHttpOrigin(baseUrl, "baseUrl");
    if (enabled && baseUrl == null) {
      throw new IllegalArgumentException("baseUrl is required when opencli is enabled");
    }
    OpenCliSettingsValidation.requireBounded(
        connectTimeoutMillis, 1L, 2L * 60 * 1000, "connectTimeoutMillis");
    OpenCliSettingsValidation.requireBounded(
        requestTimeoutMillis, 1_000L, 30L * 60 * 1000, "requestTimeoutMillis");
    OpenCliSettingsValidation.requireBounded(
        longPollTimeoutMillis, 121_000L, 10L * 60 * 1000, "longPollTimeoutMillis");
    OpenCliSettingsValidation.requireBounded(
        streamBufferBytes, 1024, 1024 * 1024, "streamBufferBytes");
    OpenCliSettingsValidation.requireBounded(
        maxJsonResponseBytes, 1024, 4 * 1024 * 1024, "maxJsonResponseBytes");
    OpenCliSettingsValidation.requireBounded(
        maxErrorResponseBytes, 256, 64 * 1024, "maxErrorResponseBytes");
    OpenCliSettingsValidation.requireBounded(maxOutputChars, 1024, 1_000_000, "maxOutputChars");
    gptImage2 = Objects.requireNonNull(gptImage2, "gptImage2");
    seedance = Objects.requireNonNull(seedance, "seedance");
  }

  static OpenCliSettings parse(String valuesJson) {
    if (valuesJson == null || valuesJson.isBlank()) {
      throw new IllegalArgumentException(
          "Invalid OpenCLI plugin configuration: malformed configuration payload");
    }
    try {
      return Objects.requireNonNull(STRICT_MAPPER.readValue(valuesJson, OpenCliSettings.class));
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Invalid OpenCLI plugin configuration: malformed configuration payload");
    }
  }

  record GptImage2(
      boolean paidEnabled,
      int askTimeoutSeconds,
      long hubExecutionTimeoutMillis,
      long maxWaitMillis) {

    GptImage2 {
      OpenCliSettingsValidation.requireBounded(askTimeoutSeconds, 1, 1740, "askTimeoutSeconds");
      OpenCliSettingsValidation.requireBounded(
          hubExecutionTimeoutMillis, 31_000L, 30L * 60 * 1000, "hubExecutionTimeoutMillis");
      OpenCliSettingsValidation.requireBounded(
          maxWaitMillis, 1_000L, 2L * 60 * 60 * 1000, "maxWaitMillis");
      if (hubExecutionTimeoutMillis / 1000L <= askTimeoutSeconds + 30L) {
        throw new IllegalArgumentException(
            "gptImage2.hubExecutionTimeoutMillis must exceed askTimeoutSeconds plus OpenCLI padding");
      }
    }
  }

  record Seedance(
      boolean enabled,
      String workspaceId,
      int retry,
      long hubExecutionTimeoutMillis,
      long statusPollIntervalMillis,
      long maxWaitMillis) {

    Seedance {
      workspaceId =
          OpenCliSettingsValidation.requireOptionalBoundedText(
              workspaceId, 256, "seedance.workspaceId");
      if (enabled && workspaceId == null) {
        throw new IllegalArgumentException(
            "seedance.workspaceId is required when seedance is enabled");
      }
      OpenCliSettingsValidation.requireBounded(retry, 0, 5, "retry");
      OpenCliSettingsValidation.requireBounded(
          hubExecutionTimeoutMillis, 1_000L, 30L * 60 * 1000, "hubExecutionTimeoutMillis");
      OpenCliSettingsValidation.requireBounded(
          statusPollIntervalMillis, 1L, 5L * 60 * 1000, "statusPollIntervalMillis");
      OpenCliSettingsValidation.requireBounded(
          maxWaitMillis, 1_000L, 4L * 60 * 60 * 1000, "maxWaitMillis");
      if (statusPollIntervalMillis >= maxWaitMillis) {
        throw new IllegalArgumentException(
            "seedance.statusPollIntervalMillis must be less than maxWaitMillis");
      }
    }
  }
}
