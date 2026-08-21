package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** 回读权威行替换快照；行缺失时保持当前快照。 */
class SystemSettingsSnapshotRefreshTest {

  private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

  @Test
  void replacesSnapshotFromAuthoritativeRow() {
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    SystemSettings updated =
        new SystemSettings(
            SystemSettings.Tool.DEFAULT,
            new SystemSettings.AiRuntime(
                9,
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
    when(repository.get())
        .thenReturn(new SystemSettingsRepository.SystemSettingsRecord(updated, 1L, NOW, NOW));

    new SystemSettingsSnapshotRefresh(repository, snapshot).run();

    assertEquals(updated, snapshot.get());
  }

  @Test
  void missingRowLeavesSnapshotUnchanged() {
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get()).thenReturn(null);

    new SystemSettingsSnapshotRefresh(repository, snapshot).run();

    assertEquals(SystemSettings.DEFAULT, snapshot.get());
  }
}
