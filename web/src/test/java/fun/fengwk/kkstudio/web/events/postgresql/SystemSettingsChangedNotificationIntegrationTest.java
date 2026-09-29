package fun.fengwk.kkstudio.web.events.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code system_settings_changed} 通知到 live 快照的端到端接线测试。
 *
 * <p>意图：另一个节点（真实 {@link PostgresqlNotificationLoop} + 生产 {@link SystemSettingsChangeHandler} + 独立
 * {@link SystemSettingsSnapshot}）必须由数据库触发器在提交后唤醒并权威回读，而不是靠进程内 afterCommit 直写内存。覆盖三条真实 PostgreSQL
 * 语义：提交的 version 推进投递通知并刷新快照；回滚事务绝不投递通知（用随后一条已提交控制通知作为投递屏障）；只改 config 不推进 version 时不投递通知，改由
 * listener 重连 resync 补齐。测试不 mock {@code pg_notify}，也不修改生产 wiring。
 */
class SystemSettingsChangedNotificationIntegrationTest extends WebPostgresTestSupport {

  private static final Duration LOOP_POLL_INTERVAL = Duration.ofMillis(50);
  private static final Duration LOOP_RECONNECT_BACKOFF = Duration.ofMillis(20);
  private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(10);

  /** 提交后 version 推进 + 改一个可读字段：触发器按 version 变化投递通知。 */
  private static final String UPDATE_VERSION_AND_RETRY_MAX_RETRIES =
      "update system_setting set version = ?,"
          + " config = jsonb_set(config, '{aiRuntime,retryMaxRetries}'::text[], to_jsonb(?::int))"
          + " where id = 1";

  /** 只改 config 不推进 version：触发器条件不成立，不投递通知。 */
  private static final String UPDATE_RETRY_MAX_RETRIES_ONLY =
      "update system_setting set"
          + " config = jsonb_set(config, '{aiRuntime,retryMaxRetries}'::text[], to_jsonb(?::int))"
          + " where id = 1";

  private static final String SELECT_VERSION = "select version from system_setting where id = 1";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private SystemSettingsRepository systemSettingsRepository;

  /** 提交的 version 推进必须通过真实 loop 投递通知，并把权威记录整体刷新进独立快照。 */
  @Test
  void committedVersionChangeNotifiesAndRefreshesOtherNodeSnapshot() {
    try (OtherNode node = startOtherNode()) {
      SystemSettings before = node.settings();
      int updatedRetries = before.aiRuntime().retryMaxRetries() + 1;
      long updatedVersion = currentVersion() + 1;

      jdbcTemplate.update(UPDATE_VERSION_AND_RETRY_MAX_RETRIES, updatedVersion, updatedRetries);

      awaitRetryMaxRetries(node, updatedRetries);
      // 通知 payload 是 version：一次提交只投递一条，且确实由数据库触发器产生。
      assertEquals(List.of(Long.toString(updatedVersion)), node.deliveredPayloads());
      // 权威回读是整体替换：未修改的 section 保持旧值，改动字段来自数据库而非通知 payload。
      assertEquals(before.tool(), node.settings().tool());
      assertEquals(before.advanced(), node.settings().advanced());
    }
  }

  /** 回滚事务绝不投递通知：用随后一条已提交控制通知作为屏障，证明投递已越过回滚点。 */
  @Test
  void rolledBackUpdateNeverDeliversNotification() throws SQLException {
    try (OtherNode node = startOtherNode()) {
      SystemSettings before = node.settings();
      long committedVersion = currentVersion();
      int abandonedRetries = before.aiRuntime().retryMaxRetries() + 5;

      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try (PreparedStatement statement =
            connection.prepareStatement(UPDATE_VERSION_AND_RETRY_MAX_RETRIES)) {
          statement.setLong(1, committedVersion + 5);
          statement.setInt(2, abandonedRetries);
          assertEquals(1, statement.executeUpdate());
        }
        connection.rollback();
      }
      assertEquals(
          committedVersion, currentVersion(), "rolled back update must not advance version");

      // 屏障：控制通知在回滚之后提交；它一旦到达即说明投递顺序已越过回滚点，回滚事务的 version 与配置绝不出现。
      int controlRetries = before.aiRuntime().retryMaxRetries() + 1;
      long controlVersion = committedVersion + 1;
      jdbcTemplate.update(UPDATE_VERSION_AND_RETRY_MAX_RETRIES, controlVersion, controlRetries);

      awaitRetryMaxRetries(node, controlRetries);
      assertEquals(
          List.of(Long.toString(controlVersion)),
          node.deliveredPayloads(),
          "rolled back update must not deliver a notification");
      assertNotEquals(abandonedRetries, node.settings().aiRuntime().retryMaxRetries());
    }
  }

  /** 不推进 version 的 config 变更不发通知，只能靠 listener 重连 resync 权威回读补齐。 */
  @Test
  void reconnectResyncCoversConfigChangeWithoutVersionBump() {
    try (OtherNode node = startOtherNode()) {
      SystemSettings before = node.settings();
      long alignedVersion = currentVersion() + 1;
      int alignedRetries = before.aiRuntime().retryMaxRetries() + 1;
      jdbcTemplate.update(UPDATE_VERSION_AND_RETRY_MAX_RETRIES, alignedVersion, alignedRetries);
      awaitRetryMaxRetries(node, alignedRetries);

      int missedRetries = alignedRetries + 1;
      jdbcTemplate.update(UPDATE_RETRY_MAX_RETRIES_ONLY, missedRetries);
      assertEquals(alignedVersion, currentVersion(), "config-only change must not advance version");
      // version 未推进 + 无新 payload：这条已提交变更确实没有通知路径。
      assertEquals(List.of(Long.toString(alignedVersion)), node.deliveredPayloads());

      // 真实重连：listener 建连后 resync 权威回读，补上遗漏的 config-only 变更。
      node.restart();

      awaitRetryMaxRetries(node, missedRetries);
      assertEquals(
          List.of(Long.toString(alignedVersion)),
          node.deliveredPayloads(),
          "catch-up must come from resync, not from a notification");
    }
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
    Long version = jdbcTemplate.queryForObject(SELECT_VERSION, Long.class);
    if (version == null) {
      throw new AssertionError("system_setting seed row must exist");
    }
    return version;
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
