package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 通知与重同步始终权威回读，并在并发回读乱序时保持快照版本单调。 */
class SystemSettingsChangeHandlerTest {

  private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
  private static final SystemSettings VERSION_ONE = settingsWithRetryMaxRetries(4);
  private static final SystemSettings VERSION_TWO = settingsWithRetryMaxRetries(5);

  /** 空值或畸形 payload 都只是 hint，必须触发同样的权威回读。 */
  @Test
  void notificationPayloadNeverPreventsAuthoritativeRefresh() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    when(repository.get()).thenReturn(record(VERSION_ONE, 1), record(VERSION_TWO, 2));
    SystemSettingsChangeHandler handler = new SystemSettingsChangeHandler(repository, snapshot);

    handler.onNotification(null);
    assertEquals(VERSION_ONE, snapshot.get());

    handler.onNotification("{not-json");
    assertEquals(VERSION_TWO, snapshot.get());
  }

  /** resync 与 notification 使用相同权威回读语义。 */
  @Test
  void resyncRefreshesFromAuthoritativeRow() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    when(repository.get()).thenReturn(record(VERSION_ONE, 1));

    new SystemSettingsChangeHandler(repository, snapshot).onResync();

    assertEquals(VERSION_ONE, snapshot.get());
  }

  /** 运行期数据库短暂失败不得杀死统一 listener 的调用线程，且后续通知仍可恢复刷新。 */
  @Test
  void refreshFailureDoesNotEscapeOrBlockLaterNotifications() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    when(repository.get())
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(record(VERSION_ONE, 1));
    SystemSettingsChangeHandler handler = new SystemSettingsChangeHandler(repository, snapshot);

    assertDoesNotThrow(() -> handler.onNotification(""));
    handler.onNotification("");

    assertEquals(VERSION_ONE, snapshot.get());
  }

  /** singleton 行异常缺失时保持已有快照，调用方可继续处理后续通知。 */
  @Test
  void missingRowLeavesSnapshotUnchanged() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    when(repository.get()).thenReturn(null);

    assertDoesNotThrow(() -> new SystemSettingsChangeHandler(repository, snapshot).onResync());

    assertEquals(SystemSettings.DEFAULT, snapshot.get());
  }

  /** 较旧回读晚于较新回读完成时，version 门控必须阻止快照回退。 */
  @Test
  void concurrentOlderReadCannotOverwriteNewerSnapshot() throws Exception {
    CountDownLatch oldReadStarted = new CountDownLatch(1);
    CountDownLatch releaseOldRead = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    SystemSettingsRepository repository =
        new SystemSettingsRepository() {
          @Override
          public SystemSettingsRecord get() {
            if (reads.incrementAndGet() == 1) {
              oldReadStarted.countDown();
              await(releaseOldRead);
              return record(VERSION_ONE, 1);
            }
            return record(VERSION_TWO, 2);
          }

          @Override
          public boolean update(SystemSettings settings, long expectedVersion) {
            throw new UnsupportedOperationException();
          }
        };
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    SystemSettingsChangeHandler handler = new SystemSettingsChangeHandler(repository, snapshot);
    Thread older = new Thread(() -> handler.onNotification("1"));

    older.start();
    if (!oldReadStarted.await(1, TimeUnit.SECONDS)) {
      throw new AssertionError("older read did not start");
    }
    handler.onNotification("2");
    releaseOldRead.countDown();
    older.join(1_000);

    assertFalse(older.isAlive(), "older refresh must complete");
    assertEquals(VERSION_TWO, snapshot.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting to release old read");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
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
