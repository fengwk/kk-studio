package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.CopyOut;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link LegacyTableCopy#binary} 多重取消失败的纯仲裁回归：用 Mockito 句柄驱动 primary 失败与两个取消失败，核对优先级与 identity。
 *
 * <p>这些用例只断言异常仲裁语义，不声称任何真实 PostgreSQL 协议行为：真实的 COPY OUT/IN 运输、外键/唯一约束拒绝与连接回滚由 {@link
 * LegacyTableCopyPostgresIntegrationTest} 覆盖。
 */
class LegacyCopyCancellationTest {

  private static final String TABLE = "legacy_copy_cancel_probe";

  private static final List<String> COLUMNS = List.of("id", "payload");

  private static final String SECRET_MARKER = "raw-sql-marker-do-not-leak";

  /** primary Error 完整保留 identity，并先压制首个取消 Error、再由首个取消 Error 压制第二个取消 Error，两个取消都被尝试。 */
  @Test
  void primaryErrorKeepsIdentityAndSuppressesBothCancellationErrors() throws SQLException {
    CopyHarness harness = copyHarness();
    Error primary = new AssertionError("primary copy failure");
    Error cancelIn = new OutOfMemoryError("cancel in failed");
    Error cancelOut = new StackOverflowError("cancel out failed");
    when(harness.copyOut().readFromCopy()).thenThrow(primary);
    doThrow(cancelIn).when(harness.copyIn()).cancelCopy();
    doThrow(cancelOut).when(harness.copyOut()).cancelCopy();

    Error failure = binary(harness);

    assertSame(primary, failure);
    verify(harness.copyIn()).cancelCopy();
    verify(harness.copyOut()).cancelCopy();
    assertEquals(1, failure.getSuppressed().length);
    assertSame(cancelIn, failure.getSuppressed()[0]);
    assertEquals(1, cancelIn.getSuppressed().length);
    assertSame(cancelOut, cancelIn.getSuppressed()[0]);
    assertEquals(0, cancelOut.getSuppressed().length);
  }

  /** primary 是非 Error 的原始 SQLException 时首个取消 Error 提升为主失败，原 SQL 只以固定安全包装入链、绝不 raw 暴露。 */
  @Test
  void rawSqlFailureIsReplacedByTheFirstCancellationErrorWithoutLeakingSql() throws SQLException {
    CopyHarness harness = copyHarness();
    SQLException raw = new SQLException("COPY failed: " + SECRET_MARKER, "42601");
    Error cancelIn = new OutOfMemoryError("cancel in failed");
    Error cancelOut = new StackOverflowError("cancel out failed");
    when(harness.copyOut().readFromCopy()).thenThrow(raw);
    doThrow(cancelIn).when(harness.copyIn()).cancelCopy();
    doThrow(cancelOut).when(harness.copyOut()).cancelCopy();

    Error failure = binary(harness);

    assertSame(cancelIn, failure);
    verify(harness.copyIn()).cancelCopy();
    verify(harness.copyOut()).cancelCopy();
    assertEquals(2, cancelIn.getSuppressed().length);
    assertSame(cancelOut, cancelIn.getSuppressed()[0]);
    LegacyDatabaseUpgradeException safe =
        assertInstanceOf(LegacyDatabaseUpgradeException.class, cancelIn.getSuppressed()[1]);
    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_WRITE, safe.stage());
    assertNull(safe.getCause());
    assertEquals("the table COPY failed before cancellation", safe.getMessage());
    assertEquals(0, cancelOut.getSuppressed().length);
    assertNoRawDisclosure(failure, raw);
  }

  /** primary 与唯一取消 Error 是同一个实例：失败不被自身取代，压制链恰好为空，且第二个取消仍被尝试。 */
  @Test
  void primaryErrorSharingIdentityWithTheCancellationErrorIsNotSelfSuppressed()
      throws SQLException {
    CopyHarness harness = copyHarness();
    Error primary = new AssertionError("primary copy failure");
    when(harness.copyOut().readFromCopy()).thenThrow(primary);
    doThrow(primary).when(harness.copyIn()).cancelCopy();

    Error failure = binary(harness);

    assertSame(primary, failure);
    verify(harness.copyIn()).cancelCopy();
    verify(harness.copyOut()).cancelCopy();
    assertEquals(0, failure.getSuppressed().length);
  }

  /** 两个取消 Error 是同一个实例：只加入一次，绝不重复压制。 */
  @Test
  void duplicateCancellationErrorInstanceIsNotAddedTwice() throws SQLException {
    CopyHarness harness = copyHarness();
    Error primary = new AssertionError("primary copy failure");
    Error shared = new OutOfMemoryError("shared cancellation error");
    when(harness.copyOut().readFromCopy()).thenThrow(primary);
    doThrow(shared).when(harness.copyIn()).cancelCopy();
    doThrow(shared).when(harness.copyOut()).cancelCopy();

    Error failure = binary(harness);

    assertSame(primary, failure);
    verify(harness.copyIn()).cancelCopy();
    verify(harness.copyOut()).cancelCopy();
    assertEquals(1, failure.getSuppressed().length);
    assertSame(shared, failure.getSuppressed()[0]);
  }

  /** copyIn 取消成功、copyOut 取消抛 Error：该 Error 仍提升为主失败并保留固定安全包装，两个句柄都被取消。 */
  @Test
  void successfulCopyInCancellationDoesNotSwallowTheCopyOutError() throws SQLException {
    CopyHarness harness = copyHarness();
    SQLException raw = new SQLException("COPY failed: " + SECRET_MARKER, "42601");
    Error cancelOut = new OutOfMemoryError("cancel out failed");
    when(harness.copyOut().readFromCopy()).thenThrow(raw);
    doThrow(cancelOut).when(harness.copyOut()).cancelCopy();

    Error failure = binary(harness);

    assertSame(cancelOut, failure);
    verify(harness.copyIn()).cancelCopy();
    verify(harness.copyOut()).cancelCopy();
    assertEquals(1, cancelOut.getSuppressed().length);
    LegacyDatabaseUpgradeException safe =
        assertInstanceOf(LegacyDatabaseUpgradeException.class, cancelOut.getSuppressed()[0]);
    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_WRITE, safe.stage());
    assertNull(safe.getCause());
    assertNoRawDisclosure(failure, raw);
  }

  private static Error binary(CopyHarness harness) {
    return assertThrows(
        Error.class,
        () -> LegacyTableCopy.binary(harness.source(), harness.target(), TABLE, COLUMNS));
  }

  /** 一个已通过 unwrap 取得 binary COPY 句柄的双连接对：源连接给出 copyOut，目标连接给出 copyIn。 */
  private static CopyHarness copyHarness() throws SQLException {
    CopyManager sourceCopy = mock(CopyManager.class);
    CopyOut copyOut = mock(CopyOut.class);
    when(sourceCopy.copyOut(anyString())).thenReturn(copyOut);
    CopyManager targetCopy = mock(CopyManager.class);
    CopyIn copyIn = mock(CopyIn.class);
    when(targetCopy.copyIn(anyString())).thenReturn(copyIn);
    return new CopyHarness(connection(sourceCopy), connection(targetCopy), copyOut, copyIn);
  }

  private static Connection connection(CopyManager copyManager) throws SQLException {
    PGConnection pgConnection = mock(PGConnection.class);
    when(pgConnection.getCopyAPI()).thenReturn(copyManager);
    Connection connection = mock(Connection.class);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    return connection;
  }

  /** 向外传播的失败链不得可达原始 SQLException，也不得带出任何标记文本或原始 cause。 */
  private static void assertNoRawDisclosure(Throwable failure, Throwable raw) {
    assertNull(failure.getCause());
    for (Throwable node : reachable(failure)) {
      assertNotSame(raw, node, "the raw SQL failure must not be reachable");
      assertFalse(node instanceof SQLException, "no raw SQLException may be exposed");
      assertFalse(
          String.valueOf(node.getMessage()).contains(SECRET_MARKER),
          "no secret marker may be exposed");
    }
  }

  /** 从失败出发遍历 suppressed 与 cause 组成的完整可达闭包，循环压制链只访问一次。 */
  private static List<Throwable> reachable(Throwable failure) {
    List<Throwable> visited = new ArrayList<>();
    Deque<Throwable> pending = new ArrayDeque<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable node = pending.removeFirst();
      if (visited.contains(node)) {
        continue;
      }
      visited.add(node);
      pending.addAll(List.of(node.getSuppressed()));
      if (node.getCause() != null) {
        pending.add(node.getCause());
      }
    }
    return visited;
  }

  /** 已配置 binary COPY 双端句柄的测试现场。 */
  private record CopyHarness(
      Connection source, Connection target, CopyOut copyOut, CopyIn copyIn) {}
}
