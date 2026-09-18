package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System settings 聚合默认值与全部校验分支：范围、跨字段约束、启用前提与 permission pattern 保存校验（negation / comment / empty /
 * invalid 形态必须在持久化前失败）。
 */
class SystemSettingsTest {

  @Test
  void defaultsMatchTheSpecification() {
    SystemSettings defaults = SystemSettings.DEFAULT;

    assertEquals(
        List.of(new PermissionRule("*", PermissionAction.ASK)),
        defaults.tool().permission().get("write"));
    assertEquals(
        List.of(new PermissionRule("*", PermissionAction.ASK)),
        defaults.tool().permission().get("edit"));
    assertEquals(
        List.of(new PermissionRule("*", PermissionAction.ASK)),
        defaults.tool().permission().get("bash"));
    assertEquals(false, defaults.tool().defaultYolo());
    assertEquals(5_000L, defaults.tool().modelGatewayBusyRetryMillis());
    assertEquals(1_000L, defaults.tool().toolGatewayBusyRetryMillis());
    assertEquals(5_000L, defaults.tool().toolGatewayOverloadRetryMillis());
    assertEquals(30_000L, defaults.tool().skillLoadTimeoutMillis());

    assertEquals(3, defaults.aiRuntime().retryMaxRetries());
    assertEquals(
        InvocationRetryBackoffStrategy.EXPONENTIAL, defaults.aiRuntime().retryBackoffStrategy());
    assertEquals(2_000L, defaults.aiRuntime().retryBaseDelayMillis());
    assertEquals(60_000L, defaults.aiRuntime().retryMaxDelayMillis());
    assertEquals(20_000, defaults.aiRuntime().compactionKeepRecentTokens());
    assertEquals(null, defaults.aiRuntime().compactionFallbackModel());
    assertEquals(2, defaults.aiRuntime().subagentMaxDepth());
    assertEquals(10, defaults.aiRuntime().subagentMaxConcurrency());
    assertEquals(0, defaults.aiRuntime().subagentMaxTotalConcurrency());
    assertEquals(0L, defaults.aiRuntime().subagentIdleTimeoutMillis());
    assertEquals(50, defaults.aiRuntime().subagentMaxTurns());

    assertEquals(16L * 1024 * 1024, defaults.environment().maxResourceBytes());
    assertEquals(60_000L, defaults.environment().heartbeatTimeoutMillis());

    SystemSettings.Integrations integrations = defaults.integrations();
    assertEquals(false, integrations.comfyui().enabled());
    assertEquals(null, integrations.comfyui().baseUrl());
    assertEquals(10_000L, integrations.comfyui().connectTimeoutMillis());
    assertEquals(30_000L, integrations.comfyui().readTimeoutMillis());
    assertEquals(1_800_000L, integrations.comfyui().websocketTimeoutMillis());
    assertEquals(50L * 1024 * 1024, integrations.comfyui().maxInputFileBytes());
    assertEquals(false, integrations.openCliHub().enabled());
    assertEquals(null, integrations.openCliHub().baseUrl());
    assertEquals(5_000L, integrations.openCliHub().connectTimeoutMillis());
    assertEquals(120_000L, integrations.openCliHub().requestTimeoutMillis());
    assertEquals(130_000L, integrations.openCliHub().longPollTimeoutMillis());
    assertEquals(16 * 1024, integrations.openCliHub().streamBufferBytes());
    assertEquals(512 * 1024, integrations.openCliHub().maxJsonResponseBytes());
    assertEquals(4096, integrations.openCliHub().maxErrorResponseBytes());
    assertEquals(65_535, integrations.openCliHub().maxOutputChars());
    assertEquals(false, integrations.seedance().enabled());
    assertEquals(null, integrations.seedance().workspaceId());
    assertEquals(0, integrations.seedance().retry());
    assertEquals(600_000L, integrations.seedance().hubExecutionTimeoutMillis());
    assertEquals(30_000L, integrations.seedance().statusPollIntervalMillis());
    assertEquals(1_800_000L, integrations.seedance().maxWaitMillis());
    assertEquals(false, integrations.gptImage2().paidEnabled());
    assertEquals(900, integrations.gptImage2().askTimeoutSeconds());
    assertEquals(960_000L, integrations.gptImage2().hubExecutionTimeoutMillis());
    assertEquals(1_200_000L, integrations.gptImage2().maxWaitMillis());
    assertEquals(false, integrations.minimaxH3().enabled());
    assertEquals(600_000L, integrations.minimaxH3().promptMaxWaitMillis());
    assertEquals(10_000L, integrations.minimaxH3().comfyConnectTimeoutMillis());
    assertEquals(30_000L, integrations.minimaxH3().comfyRequestTimeoutMillis());
    assertEquals(2_000L, integrations.minimaxH3().comfyPollIntervalMillis());
    assertEquals(1_800_000L, integrations.minimaxH3().comfyMaxWaitMillis());

    SystemSettings.StorageMedia storageMedia = defaults.storageMedia();
    assertEquals(3_600L, storageMedia.uploadExpiresSeconds());
    assertEquals(600L, storageMedia.s3PresignDefaultExpiresSeconds());
    assertEquals(3_600L, storageMedia.s3PresignMaxExpiresSeconds());
    assertEquals(30_000L, storageMedia.canvasMediaProcessTimeoutMillis());
    assertEquals(512, storageMedia.thumbnailMaxDimension());
    assertEquals(80, storageMedia.thumbnailQuality());

    SystemSettings.Advanced advanced = defaults.advanced();
    assertEquals(16L * 1024 * 1024, advanced.resourceMaxBytes());
    assertEquals(30_000L, advanced.processorLeaseDurationMillis());
    assertEquals(10_000L, advanced.processorHeartbeatIntervalMillis());
    assertEquals(512, advanced.applicationEventQueueCapacity());
    assertEquals(2L * 1024 * 1024, advanced.applicationEventMaxBytes());
    assertEquals(10_000L, advanced.applicationEventSendTimeoutMillis());
    assertEquals(20_000L, advanced.applicationEventHeartbeatIntervalMillis());
    assertEquals(5_000L, advanced.postgresqlWorkNotificationPollMillis());
    assertEquals(1_000L, advanced.postgresqlWorkReconnectBackoffMillis());
  }

  @Test
  void rejectsNegatedCommentEmptyAndInvalidPermissionPatternsBeforePersistence() {
    for (String invalid : List.of("!logs/", "#comment", " ", "[unclosed-class", "\\", "logs/\\")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> toolWithRules("write", new PermissionRule(invalid, PermissionAction.DENY)),
          invalid);
    }
    // 转义后的 literal pattern 放行（语义交给匹配器）。
    toolWithRules("write", new PermissionRule("\\!literal.txt", PermissionAction.DENY));
  }

  /** 工具名和 pattern 的首尾空白或非法字符会制造不可见、不可命中的规则，持久化边界必须拒绝。 */
  @Test
  void rejectsSurroundingWhitespaceInPermissionNamesAndPatterns() {
    assertThrows(
        IllegalArgumentException.class,
        () -> toolWithRules(" write", new PermissionRule("*", PermissionAction.ASK)));
    assertThrows(
        IllegalArgumentException.class,
        () -> toolWithRules("1write", new PermissionRule("*", PermissionAction.ASK)));
    assertThrows(
        IllegalArgumentException.class,
        () -> toolWithRules("write ", new PermissionRule("*", PermissionAction.ASK)));
    assertThrows(
        IllegalArgumentException.class,
        () -> toolWithRules("write", new PermissionRule("git status * ", PermissionAction.ASK)));
    toolWithRules("bash", new PermissionRule("git status *", PermissionAction.ASK));
  }

  @Test
  void preservesPermissionRuleOrder() {
    SystemSettings.Tool tool =
        toolWithRules(
            "write",
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("*.txt", PermissionAction.ALLOW),
            new PermissionRule("secret/**", PermissionAction.DENY));
    assertEquals(
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("*.txt", PermissionAction.ALLOW),
            new PermissionRule("secret/**", PermissionAction.DENY)),
        tool.permission().get("write"));
  }

  @Test
  void rejectsRetryMaxDelayBelowBaseDelay() {
    SystemSettings.AiRuntime base = SystemSettings.DEFAULT.aiRuntime();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.AiRuntime(
                base.retryMaxRetries(),
                base.retryBackoffStrategy(),
                60_000L,
                2_000L,
                base.compactionKeepRecentTokens(),
                base.compactionFallbackModel(),
                base.subagentMaxDepth(),
                base.subagentMaxConcurrency(),
                base.subagentMaxTotalConcurrency(),
                base.subagentIdleTimeoutMillis(),
                base.subagentMaxTurns()));
  }

  @Test
  void rejectsInvalidSubagentBudgets() {
    SystemSettings.AiRuntime base = SystemSettings.DEFAULT.aiRuntime();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.AiRuntime(
                base.retryMaxRetries(),
                base.retryBackoffStrategy(),
                base.retryBaseDelayMillis(),
                base.retryMaxDelayMillis(),
                base.compactionKeepRecentTokens(),
                base.compactionFallbackModel(),
                0,
                base.subagentMaxConcurrency(),
                base.subagentMaxTotalConcurrency(),
                base.subagentIdleTimeoutMillis(),
                base.subagentMaxTurns()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.AiRuntime(
                base.retryMaxRetries(),
                base.retryBackoffStrategy(),
                base.retryBaseDelayMillis(),
                base.retryMaxDelayMillis(),
                base.compactionKeepRecentTokens(),
                base.compactionFallbackModel(),
                base.subagentMaxDepth(),
                base.subagentMaxConcurrency(),
                -1,
                base.subagentIdleTimeoutMillis(),
                base.subagentMaxTurns()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.AiRuntime(
                base.retryMaxRetries(),
                base.retryBackoffStrategy(),
                base.retryBaseDelayMillis(),
                base.retryMaxDelayMillis(),
                base.compactionKeepRecentTokens(),
                base.compactionFallbackModel(),
                base.subagentMaxDepth(),
                base.subagentMaxConcurrency(),
                base.subagentMaxTotalConcurrency(),
                -1L,
                base.subagentMaxTurns()));
  }

  @Test
  void rejectsProcessorHeartbeatNotBelowLease() {
    SystemSettings.Advanced base = SystemSettings.DEFAULT.advanced();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.Advanced(
                base.resourceMaxBytes(),
                30_000L,
                30_000L,
                base.threadResolveFailureDelayMillis(),
                base.modelDispatchBusyFallbackDelayMillis(),
                base.toolPreflightFailureDelayMillis(),
                base.toolDispatchBusyFallbackDelayMillis(),
                base.applicationEventQueueCapacity(),
                base.applicationEventMaxBytes(),
                base.applicationEventSendTimeoutMillis(),
                base.applicationEventHeartbeatIntervalMillis(),
                base.postgresqlWorkNotificationPollMillis(),
                base.postgresqlWorkReconnectBackoffMillis()));
  }

  @Test
  void rejectsS3DefaultExpiryAboveMaxAndOutOfRangeMediaBudget() {
    SystemSettings.StorageMedia base = SystemSettings.DEFAULT.storageMedia();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.StorageMedia(
                base.uploadExpiresSeconds(),
                3_601L,
                3_600L,
                base.canvasMediaProcessTimeoutMillis(),
                base.thumbnailMaxDimension(),
                base.thumbnailQuality()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.StorageMedia(
                base.uploadExpiresSeconds(),
                base.s3PresignDefaultExpiresSeconds(),
                base.s3PresignMaxExpiresSeconds(),
                0L,
                base.thumbnailMaxDimension(),
                base.thumbnailQuality()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.StorageMedia(
                base.uploadExpiresSeconds(),
                base.s3PresignDefaultExpiresSeconds(),
                base.s3PresignMaxExpiresSeconds(),
                base.canvasMediaProcessTimeoutMillis(),
                base.thumbnailMaxDimension(),
                101));
  }

  @Test
  void rejectsEnabledIntegrationsMissingIdentityOrLocationFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.Comfyui(
                true, null, 10_000L, 30_000L, 1_800_000L, 50L * 1024 * 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemSettings.Seedance(true, null, 0, 600_000L, 30_000L, 1_800_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.OpenCliHub(
                true, null, 5_000L, 120_000L, 130_000L, 16 * 1024, 512 * 1024, 4096, 65_535));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                true, null, 600_000L, "http://comfy:8188", 10_000L, 30_000L, 2_000L, 1_800_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                true, "agent", 600_000L, null, 10_000L, 30_000L, 2_000L, 1_800_000L));
  }

  @Test
  void rejectsPollingNotBelowMaxWaitAndInvalidGptImage2Budget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemSettings.Seedance(false, null, 0, 600_000L, 1_800_000L, 1_800_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                false, null, 600_000L, null, 10_000L, 30_000L, 1_800_000L, 1_800_000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemSettings.GptImage2(false, 900, 900_000L, 1_200_000L));
  }

  @Test
  void rejectsInvalidOpenCliHubOriginAndByteRanges() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.OpenCliHub(
                false,
                "http://hub:8080/api",
                5_000L,
                120_000L,
                130_000L,
                16 * 1024,
                512 * 1024,
                4096,
                65_535));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.OpenCliHub(
                false,
                "http://user@hub:8080",
                5_000L,
                120_000L,
                130_000L,
                16 * 1024,
                512 * 1024,
                4096,
                65_535));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.OpenCliHub(
                false,
                "http://hub:8080",
                5_000L,
                120_000L,
                130_000L,
                10,
                512 * 1024,
                4096,
                65_535));
  }

  @Test
  void rejectsInvalidEnvironmentAndSeedanceBudgets() {
    assertThrows(IllegalArgumentException.class, () -> new SystemSettings.Environment(0L, 60_000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemSettings.Seedance(false, null, 6, 600_000L, 30_000L, 1_800_000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemSettings.Seedance(false, "  ws  ", 0, 600_000L, 30_000L, 1_800_000L));
  }

  @Test
  void aggregateExcludesSecretsAndBootstrapInputs() throws Exception {
    List<String> violations = new ArrayList<>();
    List<String> forbidden =
        List.of(
            "apikey",
            "accesskey",
            "secretkey",
            "bearer",
            "token",
            "instanceid",
            "credential",
            "secret",
            "endpoint",
            "region",
            "bucket",
            "workdir",
            "environmentroot",
            "tempdir",
            "binary",
            "prefix",
            "password",
            "username");
    for (RecordComponent component : SystemSettings.class.getRecordComponents()) {
      collectViolations(component.getType(), component.getName(), forbidden, violations);
    }
    assertTrue(
        violations.isEmpty(),
        "aggregate must not expose secrets or bootstrap inputs: " + violations);
  }

  private static void collectViolations(
      Class<?> type, String path, List<String> forbidden, List<String> violations) {
    boolean pluralTokenBudget = path.toLowerCase().endsWith("tokens");
    for (String forbiddenPart : forbidden) {
      if ("token".equals(forbiddenPart) && pluralTokenBudget) {
        // compactionKeepRecentTokens 是 token 预算计数，不是秘密 token。
        continue;
      }
      if (path.toLowerCase().contains(forbiddenPart)) {
        violations.add(path);
        return;
      }
    }
    if (!type.isRecord()) {
      return;
    }
    for (RecordComponent component : type.getRecordComponents()) {
      collectViolations(
          component.getType(), path + "." + component.getName(), forbidden, violations);
    }
  }

  private static SystemSettings.Tool toolWithRules(String toolId, PermissionRule... rules) {
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(toolId, List.of(rules));
    SystemSettings.Tool base = SystemSettings.DEFAULT.tool();
    return new SystemSettings.Tool(
        permission,
        base.defaultYolo(),
        base.modelGatewayBusyRetryMillis(),
        base.toolGatewayBusyRetryMillis(),
        base.toolGatewayOverloadRetryMillis(),
        base.skillLoadTimeoutMillis());
  }
}
