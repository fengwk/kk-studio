package fun.fengwk.kkstudio.web.events.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code system_settings_changed} 通知到 live 快照的端到端接线测试。
 *
 * <p>意图：另一个节点（真实 {@link PostgresqlNotificationLoop} + 生产 {@link SystemSettingsChangeHandler} + 独立
 * {@link SystemSettingsSnapshot}）必须由真实 Java 写入口提交后投递的通知唤醒并权威回读，而不是靠进程内 afterCommit 直写内存。覆盖三条真实
 * PostgreSQL 语义：提交的 version 推进投递通知并刷新快照；回滚事务绝不投递通知（用随后一条已提交控制通知作为投递屏障）；断连期间提交的变更没有投递路径，只能靠 listener
 * 重连 resync 补齐。测试不 mock {@code pg_notify}，也不修改生产 wiring。
 */
class SystemSettingsChangedNotificationIntegrationTest extends WebPostgresTestSupport {

  private static final Duration LOOP_POLL_INTERVAL = Duration.ofMillis(50);
  private static final Duration LOOP_RECONNECT_BACKOFF = Duration.ofMillis(20);
  private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(10);

  @Autowired private SystemSettingsRepository systemSettingsRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  /** 提交的 version 推进必须通过真实 loop 投递通知，并把权威记录整体刷新进独立快照。 */
  @Test
  void committedVersionChangeNotifiesAndRefreshesOtherNodeSnapshot() {
    try (OtherNode node = startOtherNode()) {
      SystemSettings before = node.settings();
      int updatedRetries = before.aiRuntime().retryMaxRetries() + 1;
      long updatedVersion = commitRetryMaxRetries(updatedRetries);

      awaitRetryMaxRetries(node, updatedRetries);
      // 通知 payload 是写后权威 version：一次提交只投递一条，且由 Java 写入口发布。
      assertEquals(List.of(Long.toString(updatedVersion)), node.deliveredPayloads());
      // 权威回读是整体替换：未修改的 section 保持旧值，改动字段来自数据库而非通知 payload。
      assertEquals(before.tool(), node.settings().tool());
      assertEquals(before.advanced(), node.settings().advanced());
    }
  }

  /** 回滚事务绝不投递通知：用随后一条已提交控制通知作为屏障，证明投递已越过回滚点。 */
  @Test
  void rolledBackUpdateNeverDeliversNotification() {
    try (OtherNode node = startOtherNode()) {
      SystemSettings before = node.settings();
      long committedVersion = currentVersion();
      int abandonedRetries = before.aiRuntime().retryMaxRetries() + 5;

      // 真实 Java 写入口在事务中推进 version，但整个事务回滚，绝不提交。
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status -> {
                assertTrue(
                    systemSettingsRepository.update(
                        withRetryMaxRetries(before, abandonedRetries), committedVersion));
                status.setRollbackOnly();
              });
      assertEquals(
          committedVersion, currentVersion(), "rolled back update must not advance version");

      // 屏障：控制通知在回滚之后提交；它一旦到达即说明投递顺序已越过回滚点，回滚事务的 version 与配置绝不出现。
      int controlRetries = before.aiRuntime().retryMaxRetries() + 1;
      long controlVersion = commitRetryMaxRetries(controlRetries);

      awaitRetryMaxRetries(node, controlRetries);
      assertEquals(
          List.of(Long.toString(controlVersion)),
          node.deliveredPayloads(),
          "rolled back update must not deliver a notification");
      assertNotEquals(abandonedRetries, node.settings().aiRuntime().retryMaxRetries());
    }
  }

  /** 断连期间提交的变更没有投递路径，只能靠 listener 重连 resync 权威回读补齐。 */
  @Test
  void reconnectResyncCoversChangePublishedWhileDisconnected() {
    try (OtherNode node = startOtherNode()) {
      SystemSettings before = node.settings();
      int alignedRetries = before.aiRuntime().retryMaxRetries() + 1;
      long alignedVersion = commitRetryMaxRetries(alignedRetries);
      awaitRetryMaxRetries(node, alignedRetries);
      assertEquals(List.of(Long.toString(alignedVersion)), node.deliveredPayloads());

      // 断开 listener 后提交：通知无法抵达本节点。
      node.stop();
      int missedRetries = alignedRetries + 1;
      long missedVersion = commitRetryMaxRetries(missedRetries);
      assertNotEquals(alignedVersion, missedVersion);
      assertEquals(
          List.of(Long.toString(alignedVersion)),
          node.deliveredPayloads(),
          "no delivery path exists while the listener is stopped");

      // 真实重连：listener 建连后 resync 权威回读，补上断连期间的变更。
      node.restart();

      awaitRetryMaxRetries(node, missedRetries);
      assertEquals(
          List.of(Long.toString(alignedVersion)),
          node.deliveredPayloads(),
          "catch-up must come from resync, not from a notification");
    }
  }

  /** 真实 Java 写入口：CAS 推进 version 并返回写后版本；不 mock 事务或通知。 */
  private long commitRetryMaxRetries(int retryMaxRetries) {
    SystemSettingsRepository.SystemSettingsRecord record = systemSettingsRepository.get();
    SystemSettings updated = withRetryMaxRetries(record.settings(), retryMaxRetries);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> assertTrue(systemSettingsRepository.update(updated, record.version())));
    return record.version() + 1;
  }

  private static SystemSettings withRetryMaxRetries(SystemSettings base, int retryMaxRetries) {
    SystemSettings.AiRuntime ai = base.aiRuntime();
    return new SystemSettings(
        base.tool(),
        new SystemSettings.AiRuntime(
            retryMaxRetries,
            ai.retryBackoffStrategy(),
            ai.retryBaseDelayMillis(),
            ai.retryMaxDelayMillis(),
            ai.compactionKeepRecentTokens(),
            ai.subagentMaxDepth(),
            ai.subagentMaxConcurrency(),
            ai.subagentMaxTotalConcurrency(),
            ai.subagentMaxTurns(),
            ai.modelHttpRetryStatusCodes()),
        base.environment(),
        base.network(),
        base.integrations(),
        base.storageMedia(),
        base.advanced());
  }

  private OtherNode startOtherNode() {
    SystemSettingsRepository.SystemSettingsRecord record = systemSettingsRepository.get();
    if (record == null) {
      throw new AssertionError("system_setting seed row must exist");
    }
    OtherNode node = new OtherNode(dataSource, systemSettingsRepository, record.settings());
    node.start();
    return node;
  }

  private long currentVersion() {
    SystemSettingsRepository.SystemSettingsRecord record = systemSettingsRepository.get();
    if (record == null) {
      throw new AssertionError("system_setting seed row must exist");
    }
    return record.version();
  }

  /** 轮询独立快照直到权威回读生效；异步投递无法用固定 sleep 证明，超时即失败。 */
  private static void awaitRetryMaxRetries(OtherNode node, int expected) {
    long deadline = System.nanoTime() + REFRESH_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (node.settings().aiRuntime().retryMaxRetries() == expected) {
        return;
      }
      sleepBeforeNextPoll();
    }
    throw new AssertionError(
        "timed out waiting for other node retryMaxRetries="
            + expected
            + " but was "
            + node.settings().aiRuntime().retryMaxRetries());
  }

  private static void sleepBeforeNextPoll() {
    try {
      Thread.sleep(LOOP_POLL_INTERVAL.toMillis());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while awaiting other node snapshot refresh", error);
    }
  }

  /**
   * 集群中的另一个节点：真实 {@link PostgresqlNotificationLoop} 连接真实 PostgreSQL，注册生产 {@link
   * SystemSettingsChangeHandler}，并持有独立 {@link SystemSettingsSnapshot}。
   *
   * <p>独立快照与测试上下文中的共享快照无关，因此版本门控从数据库当前 version 自然推导，无需为陈旧上下文让步。payload 序列被记录，用于区分 notification 与
   * resync 两条收敛路径。
   */
  private static final class OtherNode implements AutoCloseable {

    private final List<String> deliveredPayloads = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger resyncs = new AtomicInteger();
    private final SystemSettingsSnapshot snapshot;
    private final PostgresqlNotificationLoop loop;

    private OtherNode(
        DataSource dataSource,
        SystemSettingsRepository repository,
        SystemSettings initialSettings) {
      this.snapshot = new SystemSettingsSnapshot(initialSettings);
      SystemSettingsChangeHandler changeHandler =
          new SystemSettingsChangeHandler(repository, snapshot);
      this.loop =
          new PostgresqlNotificationLoop(
              dataSource,
              List.of(
                  new PostgresqlNotificationHandler(
                      SystemSettingsChangeHandler.CHANNEL,
                      payload -> {
                        deliveredPayloads.add(payload);
                        changeHandler.onNotification(payload);
                      },
                      () -> {
                        resyncs.incrementAndGet();
                        changeHandler.onResync();
                      })),
              LOOP_POLL_INTERVAL,
              LOOP_RECONNECT_BACKOFF);
    }

    private void start() {
      loop.start();
      awaitResync(1);
    }

    /** 真实重连：先停止 listener（连接被中止），再重新建连触发一次权威 resync。 */
    private void restart() {
      int before = resyncs.get();
      loop.stop();
      loop.start();
      awaitResync(before + 1);
    }

    /** 停止 listener：连接被中止，期间提交的变更没有投递路径。 */
    private void stop() {
      loop.stop();
    }

    private SystemSettings settings() {
      return snapshot.get();
    }

    private List<String> deliveredPayloads() {
      return List.copyOf(deliveredPayloads);
    }

    private void awaitResync(int expected) {
      long deadline = System.nanoTime() + REFRESH_TIMEOUT.toNanos();
      while (System.nanoTime() < deadline) {
        if (resyncs.get() >= expected) {
          return;
        }
        sleepBeforeNextPoll();
      }
      throw new AssertionError("timed out waiting for notification loop resync");
    }

    @Override
    public void close() {
      loop.close();
    }
  }
}
