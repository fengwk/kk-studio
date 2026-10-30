package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** ClusterNodePresence：节点存在判定短期缓存、过期刷新与查询失败可见性。 */
class ClusterNodePresenceTest {

  private static final UUID LOCAL = UUID.fromString("00000000-0000-0000-0000-000000000900");

  /** 缓存窗口内只查询一次，窗口过期后重新读取数据库现在时快照。 */
  @Test
  void cachesPresenceWithinWindowAndRefreshesAfterExpiry() {
    JdbcClient.MappedQuerySpec<Boolean> result = mock(JdbcClient.MappedQuerySpec.class);
    when(result.single()).thenReturn(true, false);
    JdbcClient jdbc = jdbcClientReturning(result);
    AtomicLong now = new AtomicLong(1_000_000_000L);
    ClusterNodePresence presence =
        new ClusterNodePresence(jdbc, LOCAL, Duration.ofSeconds(1), now::get);

    assertTrue(presence.hasOtherActiveNodes());
    now.addAndGet(999_999_999L);
    assertTrue(presence.hasOtherActiveNodes());
    verify(jdbc, times(1)).sql(ClusterNodePresence.HAS_OTHER_ACTIVE_NODES_SQL);

    now.addAndGet(1L);
    assertFalse(presence.hasOtherActiveNodes());
    verify(jdbc, times(2)).sql(ClusterNodePresence.HAS_OTHER_ACTIVE_NODES_SQL);
  }

  /** 单调时钟允许负起点与 long 回绕，第一次调用必须查库，缓存仍按经过时间精确过期。 */
  @Test
  void negativeOriginAndNanoTimeWrapDoNotSkipPresenceQuery() {
    for (long start : new long[] {-2_000_000_000L, Long.MAX_VALUE - 500_000_000L}) {
      JdbcClient.MappedQuerySpec<Boolean> result = mock(JdbcClient.MappedQuerySpec.class);
      when(result.single()).thenReturn(true, false);
      JdbcClient jdbc = jdbcClientReturning(result);
      AtomicLong now = new AtomicLong(start);
      ClusterNodePresence presence =
          new ClusterNodePresence(jdbc, LOCAL, Duration.ofSeconds(1), now::get);

      assertTrue(presence.hasOtherActiveNodes());
      now.addAndGet(999_999_999L);
      assertTrue(presence.hasOtherActiveNodes());
      now.incrementAndGet();
      assertFalse(presence.hasOtherActiveNodes());
      verify(jdbc, times(2)).sql(ClusterNodePresence.HAS_OTHER_ACTIVE_NODES_SQL);
    }
  }

  /** 查询失败必须向上抛出，不能在失败时假装只有本节点。 */
  @Test
  void queryFailurePropagatesAndIsNotCached() {
    JdbcClient.MappedQuerySpec<Boolean> result = mock(JdbcClient.MappedQuerySpec.class);
    IllegalStateException failure = new IllegalStateException("database unavailable");
    when(result.single()).thenThrow(failure).thenReturn(true);
    JdbcClient jdbc = jdbcClientReturning(result);
    AtomicLong now = new AtomicLong(1_000_000_000L);
    ClusterNodePresence presence =
        new ClusterNodePresence(jdbc, LOCAL, Duration.ofSeconds(1), now::get);

    assertThrows(IllegalStateException.class, presence::hasOtherActiveNodes);
    assertTrue(presence.hasOtherActiveNodes());
    verify(jdbc, times(2)).sql(ClusterNodePresence.HAS_OTHER_ACTIVE_NODES_SQL);
  }

  @Test
  void rejectsNonPositiveCacheTtl() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClusterNodePresence(
                mock(JdbcClient.class), LOCAL, Duration.ZERO, System::nanoTime));
  }

  /** 固定 SQL 与绑定参数，测试只观察查询次数与数据库返回值。 */
  private static JdbcClient jdbcClientReturning(JdbcClient.MappedQuerySpec<Boolean> result) {
    JdbcClient jdbc = mock(JdbcClient.class);
    JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    when(jdbc.sql(ClusterNodePresence.HAS_OTHER_ACTIVE_NODES_SQL)).thenReturn(statement);
    when(statement.param("localNodeId", LOCAL)).thenReturn(statement);
    when(statement.query(Boolean.class)).thenReturn(result);
    return jdbc;
  }
}
