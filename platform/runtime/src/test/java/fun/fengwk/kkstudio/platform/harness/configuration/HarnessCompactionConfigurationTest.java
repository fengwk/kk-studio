package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.compaction.TokenEstimator;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Platform 的压缩与请求预算端口 bean 定义：{@link CompactionConfigProvider} 由共享快照 SystemSettings.AiRuntime
 * 的保留上下文配置驱动，可被 platform resolver 与 web 组合根共享、每次决策点现读；{@link RequestTokenEstimator} 绑定内核唯一的估算实现，供
 * Turn 解析注入。
 */
class HarnessCompactionConfigurationTest {

  private static final UUID SESSION_ID = new UUID(0L, 10L);
  private static final UUID ROOT_ID = new UUID(0L, 11L);
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("builtin", "{}"), null);

  @Test
  void providesCompactionConfigBeanFromSystemSettingsDefaults() {
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class,
            () -> new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT))
        .run(
            context -> {
              CompactionConfigProvider provider = context.getBean(CompactionConfigProvider.class);
              CompactionConfig config = provider.compactionConfig();
              assertEquals(20_000, config.keepRecentTokens());
            });
  }

  @Test
  void compactionConfigBeanFollowsSystemSettingsOverrides() {
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class,
            () -> new SystemSettingsSnapshot(settingsWithCompaction(8_192)))
        .run(
            context -> {
              CompactionConfig config =
                  context.getBean(CompactionConfigProvider.class).compactionConfig();
              assertEquals(8_192, config.keepRecentTokens());
            });
  }

  /** 端口 bean 直接绑定内核估算实现：同一路径与 system instruction 得到与正式发送完全相同的结果。 */
  @Test
  void requestTokenEstimatorBeanBindsTheKernelEstimation() {
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class,
            () -> new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT))
        .run(
            context -> {
              RequestTokenEstimator estimator = context.getBean(RequestTokenEstimator.class);
              EntryPath path = rootOnlyPath();
              long delegated = TokenEstimator.estimateRequestTokens(path, "system");
              assertEquals(delegated, estimator.estimateRequestTokens(path, "system"));
              assertEquals(2L, delegated);
            });
  }

  /** ROOT-only 路径：没有任何上下文消息，估算只剩 system instruction 自身的 token。 */
  private static EntryPath rootOnlyPath() {
    Entry root = new Entry(ROOT_ID, SESSION_ID, null, new RootPayload(SETTINGS), Instant.EPOCH);
    return new EntryPath(List.of(root));
  }

  private static SystemSettings settingsWithCompaction(int keepRecentTokens) {
    SystemSettings.AiRuntime aiRuntime =
        new SystemSettings.AiRuntime(
            3,
            SystemSettingsTestFixture.AI_RUNTIME.retryBackoffStrategy(),
            SystemSettingsTestFixture.AI_RUNTIME.retryBaseDelayMillis(),
            SystemSettingsTestFixture.AI_RUNTIME.retryMaxDelayMillis(),
            SystemSettingsTestFixture.AI_RUNTIME.compactionModel(),
            keepRecentTokens,
            SystemSettingsTestFixture.AI_RUNTIME.subagentMaxDepth(),
            SystemSettingsTestFixture.AI_RUNTIME.subagentMaxConcurrency(),
            SystemSettingsTestFixture.AI_RUNTIME.subagentMaxTotalConcurrency(),
            SystemSettingsTestFixture.AI_RUNTIME.subagentMaxTurns(),
            SystemSettingsTestFixture.AI_RUNTIME.modelHttpRetryStatusCodes());
    return new SystemSettings(
        SystemSettingsTestFixture.TOOL,
        aiRuntime,
        SystemSettingsTestFixture.ENVIRONMENT,
        SystemSettingsTestFixture.BACKEND_NETWORK,
        SystemSettingsTestFixture.STORAGE_MEDIA,
        SystemSettingsTestFixture.ADVANCED);
  }
}
