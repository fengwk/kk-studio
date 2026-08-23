package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** 快照保存权威版本并只接受不旧于当前值的数据库记录。 */
class SystemSettingsSnapshotTest {

  private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
  private static final SystemSettings VERSION_ONE = settingsWithRetryMaxRetries(4);
  private static final SystemSettings VERSION_TWO = settingsWithRetryMaxRetries(5);

  /** 启动快照必须携带 repository record version，后续旧记录不能覆盖。 */
  @Test
  void initializesWithRepositoryVersionAndRejectsOlderRecord() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get()).thenReturn(record(VERSION_TWO, 2));
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(repository);

    assertFalse(snapshot.replaceIfNotOlder(record(VERSION_ONE, 1)));
    assertEquals(VERSION_TWO, snapshot.get());
    assertTrue(snapshot.replaceIfNotOlder(record(VERSION_TWO, 2)));
  }

  /** singleton 行缺失属于启动不变量错误，必须立即失败。 */
  @Test
  void missingRepositoryRowFailsStartup() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get()).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> new SystemSettingsSnapshot(repository));
  }

  /** 手动替换只改变配置并保留当前版本门控。 */
  @Test
  void manualReplaceKeepsVersionGate() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get()).thenReturn(record(VERSION_TWO, 2));
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(repository);

    snapshot.replace(SystemSettings.DEFAULT);

    assertEquals(SystemSettings.DEFAULT, snapshot.get());
    assertFalse(snapshot.replaceIfNotOlder(record(VERSION_ONE, 1)));
    assertEquals(SystemSettings.DEFAULT, snapshot.get());
  }

  private static SystemSettingsRepository.SystemSettingsRecord record(
      SystemSettings settings, long version) {
    return new SystemSettingsRepository.SystemSettingsRecord(settings, version, NOW, NOW);
  }

  private static SystemSettings settingsWithRetryMaxRetries(int retryMaxRetries) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        new SystemSettings.AiRuntime(
            retryMaxRetries,
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.compactionKeepRecentTokens(),
            SystemSettings.AiRuntime.DEFAULT.compactionFallbackModel(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxDepth(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTotalConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentIdleTimeoutMillis(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTurns()),
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
